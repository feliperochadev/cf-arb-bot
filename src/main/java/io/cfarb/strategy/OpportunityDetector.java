package io.cfarb.strategy;

import io.cfarb.book.BookRegistry;
import io.cfarb.book.L2Book;
import io.cfarb.graph.Triangle;
import io.cfarb.graph.TriangleRegistry;
import io.cfarb.journal.EventJournal;
import io.cfarb.journal.JournalEvents;
import io.cfarb.metrics.BotMetrics;
import io.cfarb.model.OrderIntent;
import io.cfarb.risk.RiskGates;
import io.cfarb.state.Portfolio;
import org.jctools.queues.SpscArrayQueue;

/**
 * The hot-path entry point (cf-arb-bot-plan.md §5.1): called on the single Netty event-loop thread
 * immediately after a book update, for exactly the triangles that touch the updated symbol
 * (via {@link TriangleRegistry#trianglesForSymbol}, no map lookup). Zero allocation until an
 * actual fire decision is made, at which point one {@link OrderIntent} is allocated and handed to
 * the executor thread via the SPSC queue.
 *
 * <p>Order of checks matters: cheap gates first ({@link RiskGates#canFire}: kill switch, open-cycle
 * cap, cooldown, rate limit — all O(1)) BEFORE the relatively expensive {@link EdgeCalculator}
 * ladder walk, so a triangle that's on cooldown or already has a cycle open never pays for VWAP
 * computation it can't act on anyway.
 */
public final class OpportunityDetector {

    private final BookRegistry books;
    private final TriangleRegistry triangles;
    private final RiskGates riskGates;
    private final Portfolio portfolio;
    private final BotMetrics metrics;
    private final EventJournal journal;
    private final SpscArrayQueue<OrderIntent> orderQueue;

    private final double minNetBps;
    private final double slippageBufferBps;
    private final boolean compound;
    private final long seedFixed;
    private final long maxNotionalFixed;

    private final EdgeCalculator edgeCalculator = new EdgeCalculator();
    private final EdgeCalculator.Result edgeResult = new EdgeCalculator.Result();

    /** Third-pass review finding: per-triangle timestamp of the last REJECT journaled, so the
     * reject stream is sampled rather than emitted at full feed rate. See {@link #journalReject}.
     * Detector-thread-only, like every other counter here -- no synchronization. */
    private final long[] lastRejectJournalNanos;
    private final long rejectJournalIntervalNanos;

    public OpportunityDetector(BookRegistry books, TriangleRegistry triangles, RiskGates riskGates,
                                Portfolio portfolio, BotMetrics metrics, EventJournal journal,
                                SpscArrayQueue<OrderIntent> orderQueue,
                                double minNetBps, double slippageBufferBps, boolean compound,
                                long rejectJournalIntervalMs) {
        this.books = books;
        this.triangles = triangles;
        this.riskGates = riskGates;
        this.portfolio = portfolio;
        this.metrics = metrics;
        this.journal = journal;
        this.orderQueue = orderQueue;
        this.minNetBps = minNetBps;
        this.slippageBufferBps = slippageBufferBps;
        this.compound = compound;
        this.seedFixed = portfolio.seed();
        this.maxNotionalFixed = io.cfarb.util.FixedPoint.fromDouble(riskGates.effectiveMaxNotionalUsd);
        this.rejectJournalIntervalNanos = rejectJournalIntervalMs * 1_000_000L;
        this.lastRejectJournalNanos = new long[Math.max(1, triangles.triangleCount())];
        // Same "0 is not a safe never-happened sentinel" reasoning as RiskGates' cooldown array:
        // nanoTime's origin is arbitrary per JVM. Seed far enough in the past that the FIRST reject
        // for every triangle is always journaled, while staying clear of subtraction overflow.
        java.util.Arrays.fill(lastRejectJournalNanos, Long.MIN_VALUE / 2);
    }

    /** Called after {@code symbolIndex}'s book has just been updated. {@code nowNanos} must be
     * {@code System.nanoTime()} at frame receipt (security rule S14). */
    public void onBookUpdated(int symbolIndex, long nowNanos) {
        int[] touched = triangles.trianglesForSymbol(symbolIndex);
        for (int triangleIndex : touched) {
            evaluate(triangleIndex, nowNanos);
        }
    }

    private void evaluate(int triangleIndex, long nowNanos) {
        Triangle tri = triangles.triangle(triangleIndex);
        if (!allLegsFresh(tri, nowNanos)) {
            return; // fail closed: a stale/crossed/untrusted leg never reaches EdgeCalculator (S5)
        }

        // cf-arb-bot-review-plan.md Tier 2 step 2.7: the previous version always sized at the FULL
        // current equity and simply stopped firing once that exceeded max-notional-usd -- with a
        // $100 seed and a $200 cap, compounding past $200 silently halted the bot entirely instead
        // of sizing down. Size at min(eligible anchor balance, max-notional); compound=false pins
        // the eligible balance to the original seed rather than the compounding equity.
        long eligibleBalance = compound ? portfolio.equity() : seedFixed;
        long candidateNotional = Math.min(eligibleBalance, maxNotionalFixed);
        if (!riskGates.canFire(triangleIndex, candidateNotional, nowNanos)) {
            return;
        }

        edgeCalculator.evaluate(tri, books, candidateNotional, edgeResult);
        if (!edgeResult.fillable) {
            metrics.recordOpportunityRejectedUnfillable();
            // cf-arb-bot-review-plan.md Tier 2 step 2.6 / plan §8: "the rejects are the interesting
            // half" -- but SAMPLED, not one line per candidate per frame (see journalReject).
            // netBps is NaN here (no fill), but grossBps still carries the top-of-book edge so an
            // "unfillable" reject is distinguishable from "no edge at all".
            journalReject(triangleIndex, tri.name(), Double.NaN, edgeResult.grossBps, candidateNotional,
                    "unfillable", nowNanos);
            return; // top-of-book may have looked good, but the real ladder/lot-size can't fill it
        }
        metrics.recordOpportunityDetected();
        if (edgeResult.netBps <= minNetBps + slippageBufferBps) {
            journalReject(triangleIndex, tri.name(), edgeResult.netBps, edgeResult.grossBps,
                    candidateNotional, "below-threshold", nowNanos);
            return; // real edge doesn't clear threshold + slippage buffer
        }

        // Claim the fire slot NOW, immediately before handing off -- canFire() above is a
        // non-claiming pre-check so a candidate that fails EdgeCalculator never burns real
        // cooldown/rate-limit budget (see RiskGates.canFire's javadoc).
        riskGates.claim(triangleIndex, nowNanos);
        long[] legPrices = java.util.Arrays.copyOf(edgeResult.legWorstPriceFixed, 3);
        long[] legBaseQty = java.util.Arrays.copyOf(edgeResult.legBaseQtyFixed, 3);
        OrderIntent intent = new OrderIntent(nowNanos, triangleIndex, candidateNotional, edgeResult.netBps,
                legPrices, legBaseQty);
        if (!orderQueue.offer(intent)) {
            riskGates.onCycleFinished(); // roll back the claim -- we couldn't even enqueue it
            metrics.recordOrderQueueDrop();
            journal.write(JournalEvents.opportunity(tri.name(), edgeResult.netBps, edgeResult.grossBps,
                    candidateNotional, false, "order-queue-full"));
            return;
        }
        metrics.recordOpportunityFired();
        journal.write(JournalEvents.opportunity(tri.name(), edgeResult.netBps, edgeResult.grossBps,
                candidateNotional, true, null));
    }

    /**
     * Journal a REJECTED candidate, at most once per triangle per {@code cf-bot.journal.reject-sample-ms}.
     *
     * <p><b>Third-pass review finding.</b> Every candidate that clears the cheap risk gates reaches
     * one of the two reject paths, and {@code canFire}'s per-triangle cooldown only advances on
     * {@link RiskGates#claim} — so a triangle that never fires was writing a journal line on EVERY
     * book update it touched. At the measured feed rate (aggre.depth@10ms, 14-41ms per symbol) over
     * ~12 symbols and ~14 triangles that is roughly 1k+ lines/second, ~10 GB/day against a 20 GB root
     * volume — in dry-run, the default mode. It also put ~1k {@code String} allocations/second
     * (JournalEvents' formatting) on the Netty event-loop thread, which rule R1 forbids outright.
     *
     * <p>Fires and genuine anomalies (order-queue-full) stay UNSAMPLED — they are rare by
     * construction and are the events an operator actually reconstructs a session from. Suppressed
     * rejects are counted ({@code cfarb.journal.suppressed}), never silently dropped:
     * recorder-service non-negotiable #2, "a dropped frame that isn't counted is a lie."
     */
    private void journalReject(int triangleIndex, String name, double netBps, double grossBps,
                                long candidateNotional, String reason, long nowNanos) {
        if (nowNanos - lastRejectJournalNanos[triangleIndex] < rejectJournalIntervalNanos) {
            metrics.recordJournalSuppressed();
            return;
        }
        lastRejectJournalNanos[triangleIndex] = nowNanos;
        journal.write(JournalEvents.opportunity(name, netBps, grossBps, candidateNotional, false, reason));
    }

    private boolean allLegsFresh(Triangle tri, long nowNanos) {
        for (int symbolIndex : tri.symbolIndex()) {
            L2Book book = books.book(symbolIndex);
            if (!book.isTrusted() || book.isEmpty() || book.isCrossed()) {
                return false;
            }
            if (!riskGates.isBookFresh(book.ageNanos(nowNanos))) {
                return false;
            }
        }
        return true;
    }
}

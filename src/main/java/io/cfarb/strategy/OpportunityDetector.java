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

    public OpportunityDetector(BookRegistry books, TriangleRegistry triangles, RiskGates riskGates,
                                Portfolio portfolio, BotMetrics metrics, EventJournal journal,
                                SpscArrayQueue<OrderIntent> orderQueue,
                                double minNetBps, double slippageBufferBps, boolean compound) {
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
            // half" -- journal every candidate that reaches this point (past the cheap risk gates),
            // not only the ones that fire.
            journal.write(JournalEvents.opportunity(tri.name(), Double.NaN, candidateNotional, false, "unfillable"));
            return; // top-of-book may have looked good, but the real ladder/lot-size can't fill it
        }
        metrics.recordOpportunityDetected();
        if (edgeResult.netBps <= minNetBps + slippageBufferBps) {
            journal.write(JournalEvents.opportunity(tri.name(), edgeResult.netBps, candidateNotional, false,
                    "below-threshold"));
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
            journal.write(JournalEvents.opportunity(tri.name(), edgeResult.netBps, candidateNotional, false,
                    "order-queue-full"));
            return;
        }
        metrics.recordOpportunityFired();
        journal.write(JournalEvents.opportunity(tri.name(), edgeResult.netBps, candidateNotional, true, null));
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

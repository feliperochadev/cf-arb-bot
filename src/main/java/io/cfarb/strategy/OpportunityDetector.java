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

    /** JOURNAL-TUNING-TASK.md T2 reject-reason codes, stored in a primitive array so the pending
     * window best carries no {@code String} until it is actually emitted. */
    private static final int REASON_UNFILLABLE = 0;
    private static final int REASON_BELOW_THRESHOLD = 1;

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

    private final EdgeCalculator edgeCalculator = new EdgeCalculator();
    private final EdgeCalculator.Result edgeResult = new EdgeCalculator.Result();

    private final long rejectJournalIntervalNanos;

    // --- JOURNAL-TUNING-TASK.md T2: per-triangle running-best reject inside each sample window ---
    // Detector-thread-only, like every counter here -- no synchronization. Emitted (one NDJSON line
    // summarising the whole window) edge-triggered by the first candidate AFTER the window expires;
    // a triangle that goes quiet holds its last window unemitted, which is acceptable -- the line
    // carries sampled_from so a reader knows how many ticks it represents.
    private final long[] windowStartNanos;
    private final int[] pendingSampledFrom;
    private final boolean[] pendingFillable;
    private final double[] pendingNetBps;
    private final double[] pendingGrossBps;
    private final long[] pendingNotional;
    private final int[] pendingReason;
    // T4 per-leg depth, flat [triangleIndex*3 + leg] -- snapshotted from edgeResult whenever a
    // candidate becomes the new window best.
    private final long[] pendingLegTopPx;
    private final long[] pendingLegTouchQty;
    private final long[] pendingLegWorstPx;
    private final long[] pendingLegBaseQty;
    // Reusable length-3 scratch for handing one triangle's leg slice to JournalEvents on emit --
    // allocated once, never on a per-candidate path.
    private final long[] emitLegTopPx = new long[3];
    private final long[] emitLegTouchQty = new long[3];
    private final long[] emitLegWorstPx = new long[3];
    private final long[] emitLegBaseQty = new long[3];

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
        this.rejectJournalIntervalNanos = rejectJournalIntervalMs * 1_000_000L;

        int n = Math.max(1, triangles.triangleCount());
        this.windowStartNanos = new long[n];
        this.pendingSampledFrom = new int[n];
        this.pendingFillable = new boolean[n];
        this.pendingNetBps = new double[n];
        this.pendingGrossBps = new double[n];
        this.pendingNotional = new long[n];
        this.pendingReason = new int[n];
        this.pendingLegTopPx = new long[n * 3];
        this.pendingLegTouchQty = new long[n * 3];
        this.pendingLegWorstPx = new long[n * 3];
        this.pendingLegBaseQty = new long[n * 3];
        // Same "0 is not a safe never-happened sentinel" reasoning as RiskGates' cooldown array:
        // nanoTime's origin is arbitrary per JVM. pendingSampledFrom==0 already means "no window
        // open", so windowStartNanos only matters once a window is open -- but seed it clear of
        // subtraction overflow anyway.
        java.util.Arrays.fill(windowStartNanos, Long.MIN_VALUE / 2);
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
        int staleLeg = firstStaleLeg(tri, nowNanos);
        if (staleLeg >= 0) {
            // JOURNAL-TUNING-TASK.md T3: the single most common evaluate() outcome, previously
            // silent -- §5's whole duty-cycle analysis had to be reconstructed from absent rows.
            metrics.recordDetectorStaleSkip(triangleIndex);
            metrics.recordDetectorStaleSkipLeg(tri.symbolIndex()[staleLeg]);
            return; // fail closed: a stale/crossed/untrusted leg never reaches EdgeCalculator (S5)
        }

        // cf-arb-bot-review-plan.md Tier 2 step 2.7: size at min(eligible anchor balance, cap);
        // compound=false pins the eligible balance to the original seed rather than the compounding
        // equity. JOURNAL-TUNING-TASK.md T5: the cap is now per-triangle (defaults to the global
        // cf-bot.risk.max-notional-usd), so a thin cross triangle can be sized smaller than a deep
        // one -- JOURNAL-BPS-ANALYSIS.md §3.1 measured drag scaling ~linearly with size.
        long eligibleBalance = compound ? portfolio.equity() : seedFixed;
        long candidateNotional = Math.min(eligibleBalance, triangles.maxNotionalFixed(triangleIndex));
        if (!riskGates.canFire(triangleIndex, candidateNotional, nowNanos)) {
            return;
        }

        edgeCalculator.evaluate(tri, books, candidateNotional, edgeResult);
        if (!edgeResult.fillable) {
            metrics.recordOpportunityRejectedUnfillable();
            journalReject(triangleIndex, tri.name(), false, Double.NaN, edgeResult.grossBps,
                    candidateNotional, REASON_UNFILLABLE, nowNanos);
            return; // top-of-book may have looked good, but the real ladder/lot-size can't fill it
        }
        metrics.recordOpportunityDetected();
        if (edgeResult.netBps <= minNetBps + slippageBufferBps) {
            journalReject(triangleIndex, tri.name(), true, edgeResult.netBps, edgeResult.grossBps,
                    candidateNotional, REASON_BELOW_THRESHOLD, nowNanos);
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
                    candidateNotional, false, "order-queue-full", 0,
                    edgeResult.legTopPriceFixed, edgeResult.legTouchQtyFixed,
                    edgeResult.legWorstPriceFixed, edgeResult.legBaseQtyFixed));
            return;
        }
        metrics.recordOpportunityFired();
        journal.write(JournalEvents.opportunity(tri.name(), edgeResult.netBps, edgeResult.grossBps,
                candidateNotional, true, null, 0,
                edgeResult.legTopPriceFixed, edgeResult.legTouchQtyFixed,
                edgeResult.legWorstPriceFixed, edgeResult.legBaseQtyFixed));
    }

    /**
     * Journal a REJECTED candidate — at most one NDJSON line per triangle per
     * {@code cf-bot.journal.reject-sample-ms}, carrying the BEST candidate seen in that window
     * (JOURNAL-TUNING-TASK.md T2) plus {@code sampled_from} and per-leg depth (T4).
     *
     * <p><b>Why the change.</b> The previous form kept the FIRST reject in each window and dropped
     * the rest — a uniform ~1 Hz sample of a ~300/s stream, so every peak was invisible
     * (JOURNAL-BPS-ANALYSIS.md §9.1: 97.1 % of evaluations discarded). Tuning needs the tail. Same
     * line count, same file size; the running best lives in the primitive arrays above, no object
     * per candidate and no {@code String} formatting until a window actually closes.
     *
     * <p>Suppressed rejects are still counted ({@code cfarb.journal.suppressed}) — the first
     * candidate of a window is the provisional keep and is not counted as suppressed, every
     * subsequent one folded into the window best is, exactly mirroring the old first-vs-rest
     * semantics (recorder-service non-negotiable #2: a dropped frame that isn't counted is a lie).
     */
    private void journalReject(int triangleIndex, String name, boolean fillable, double netBps,
                               double grossBps, long candidateNotional, int reason, long nowNanos) {
        if (rejectJournalIntervalNanos <= 0) {
            // Sampling disabled: journal every reject immediately (only sane for short local captures).
            emitNow(name, fillable, netBps, grossBps, candidateNotional, reason, 1);
            return;
        }
        if (pendingSampledFrom[triangleIndex] == 0) {
            windowStartNanos[triangleIndex] = nowNanos;
            setPending(triangleIndex, fillable, netBps, grossBps, candidateNotional, reason);
            pendingSampledFrom[triangleIndex] = 1;
            return;
        }
        if (nowNanos - windowStartNanos[triangleIndex] >= rejectJournalIntervalNanos) {
            emitPending(triangleIndex, name);
            windowStartNanos[triangleIndex] = nowNanos;
            setPending(triangleIndex, fillable, netBps, grossBps, candidateNotional, reason);
            pendingSampledFrom[triangleIndex] = 1;
            return;
        }
        if (isBetterThanPending(triangleIndex, fillable, netBps, grossBps)) {
            setPending(triangleIndex, fillable, netBps, grossBps, candidateNotional, reason);
        }
        pendingSampledFrom[triangleIndex]++;
        metrics.recordJournalSuppressed();
    }

    /** A fillable (below-threshold) candidate always beats an unfillable one — it is strictly
     * closer to firing. Within the same fillability, rank by the metric that matters: {@code netBps}
     * for a fillable candidate, {@code grossBps} for an unfillable one (which has no {@code netBps}). */
    private boolean isBetterThanPending(int tri, boolean fillable, double netBps, double grossBps) {
        boolean pf = pendingFillable[tri];
        if (fillable != pf) {
            return fillable;
        }
        return fillable ? netBps > pendingNetBps[tri] : grossBps > pendingGrossBps[tri];
    }

    private void setPending(int tri, boolean fillable, double netBps, double grossBps,
                            long notional, int reason) {
        pendingFillable[tri] = fillable;
        pendingNetBps[tri] = netBps;
        pendingGrossBps[tri] = grossBps;
        pendingNotional[tri] = notional;
        pendingReason[tri] = reason;
        int base = tri * 3;
        System.arraycopy(edgeResult.legTopPriceFixed, 0, pendingLegTopPx, base, 3);
        System.arraycopy(edgeResult.legTouchQtyFixed, 0, pendingLegTouchQty, base, 3);
        System.arraycopy(edgeResult.legWorstPriceFixed, 0, pendingLegWorstPx, base, 3);
        System.arraycopy(edgeResult.legBaseQtyFixed, 0, pendingLegBaseQty, base, 3);
    }

    private void emitPending(int tri, String name) {
        int base = tri * 3;
        System.arraycopy(pendingLegTopPx, base, emitLegTopPx, 0, 3);
        System.arraycopy(pendingLegTouchQty, base, emitLegTouchQty, 0, 3);
        System.arraycopy(pendingLegWorstPx, base, emitLegWorstPx, 0, 3);
        System.arraycopy(pendingLegBaseQty, base, emitLegBaseQty, 0, 3);
        journal.write(JournalEvents.opportunity(name, pendingNetBps[tri], pendingGrossBps[tri],
                pendingNotional[tri], false, reasonString(pendingReason[tri]), pendingSampledFrom[tri],
                emitLegTopPx, emitLegTouchQty, emitLegWorstPx, emitLegBaseQty));
        pendingSampledFrom[tri] = 0;
    }

    /** Sampling-disabled path (interval 0): the current candidate is written as its own line. */
    private void emitNow(String name, boolean fillable, double netBps, double grossBps,
                         long notional, int reason, int sampledFrom) {
        journal.write(JournalEvents.opportunity(name, netBps, grossBps, notional, false,
                reasonString(reason), sampledFrom,
                edgeResult.legTopPriceFixed, edgeResult.legTouchQtyFixed,
                edgeResult.legWorstPriceFixed, edgeResult.legBaseQtyFixed));
    }

    private static String reasonString(int reason) {
        return reason == REASON_UNFILLABLE ? "unfillable" : "below-threshold";
    }

    /** JOURNAL-TUNING-TASK.md T3: returns the index (0..2) of the first leg that is stale /
     * untrusted / empty / crossed, or -1 if every leg is fresh. */
    private int firstStaleLeg(Triangle tri, long nowNanos) {
        int[] symbolIndex = tri.symbolIndex();
        for (int leg = 0; leg < 3; leg++) {
            L2Book book = books.book(symbolIndex[leg]);
            if (!book.isTrusted() || book.isEmpty() || book.isCrossed()) {
                return leg;
            }
            if (!riskGates.isBookFresh(book.ageNanos(nowNanos))) {
                return leg;
            }
        }
        return -1;
    }
}

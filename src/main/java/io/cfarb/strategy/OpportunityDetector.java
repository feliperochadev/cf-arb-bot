package io.cfarb.strategy;

import io.cfarb.book.BookRegistry;
import io.cfarb.book.L2Book;
import io.cfarb.graph.Triangle;
import io.cfarb.graph.TriangleRegistry;
import io.cfarb.journal.EventJournal;
import io.cfarb.journal.JournalEvents;
import io.cfarb.metrics.BotMetrics;
import io.cfarb.model.OrderIntent;
import io.cfarb.model.Side;
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
    /** DUPLICATE-FIRE-TASK.md ("Fix A"): a candidate that cleared the threshold but whose fire
     * signature is unchanged since the triangle's last fire — the book has not rewritten the
     * liquidity that fire aimed at, so re-firing would only race depth that is being consumed. */
    private static final int REASON_DUPLICATE = 2;
    /** DYNAMIC-SIZING-TASK.md Phase 1: {@code grossBps} alone already proved no size could clear the
     * gate, so the ladder walk never ran — distinct from {@link #REASON_UNFILLABLE}, which means the
     * walk DID run and failed. Must not increment {@code recordOpportunityRejectedUnfillable()}. */
    private static final int REASON_GROSS_BAIL = 3;

    // PRE-LIVE-PLAN.md P0-2(b): defaults for the convenience constructors below, matching
    // BotConfig.DetectorConfig's own @WithDefault values.
    private static final double DEFAULT_DUPLICATE_MATERIAL_FRACTION = 0.10;
    private static final long DEFAULT_DUPLICATE_WINDOW_MS = 30_000;
    // PRE-LIVE-PLAN.md P0-2(d): ditto, for the stale-leg guard.
    private static final long DEFAULT_STALE_LEG_FROZEN_MS = 1_000;
    private static final long DEFAULT_STALE_LEG_ACTIVE_MS = 200;

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
    // PRE-LIVE-PLAN.md P0-2(c): refuse a triangle with a leg that reset (crossed-latch self-heal, a
    // version-chain gap, or reconnect) more recently than this, regardless of isTrusted() -- see
    // L2Book#lastResetNanos's javadoc. 0 disables the quarantine (BotService logs a startup WARN).
    private final long postResetQuarantineNanos;

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
    // DYNAMIC-SIZING-TASK.md Phase 2: size_candidates / net_bps_at_cap, snapshotted from edgeResult
    // alongside the rest of the window-best state above.
    private final int[] pendingSizeCandidates;
    private final double[] pendingNetBpsAtCap;
    // Reusable length-3 scratch for handing one triangle's leg slice to JournalEvents on emit --
    // allocated once, never on a per-candidate path.
    private final long[] emitLegTopPx = new long[3];
    private final long[] emitLegTouchQty = new long[3];
    private final long[] emitLegWorstPx = new long[3];
    private final long[] emitLegBaseQty = new long[3];

    // --- DYNAMIC-SIZING-TASK.md Phase 1: force one full (non-bailed) evaluation per triangle per
    // reject-sample window, so JOURNAL-BPS-ANALYSIS.md's paired (gross_bps, net_bps) drag diagnostic
    // keeps appearing even though most ticks now skip the ladder walk entirely (the gross early-out
    // makes ~99.5% of ticks provably unable to clear the gate, so their ladder walk is dead code at
    // runtime -- see EdgeCalculator#evaluate's javadoc). Same "0 is not a safe sentinel" reasoning as
    // windowStartNanos above.
    private final long[] lastFullEvalNanos;

    // --- DUPLICATE-FIRE-TASK.md ("Fix A"): per-triangle last-fired signature ------------------
    // Detector-thread-only, like every array here -- no synchronization. Flat [triangleIndex*3 + leg]
    // for the three per-leg components; one boolean per triangle marks whether a signature has been
    // stored yet (a triangle that has never fired can never be a "duplicate"). Stored ONLY after a
    // successful orderQueue.offer() -- an intent that could not be enqueued is rolled back via
    // riskGates.onCycleFinished() and must stay re-fireable. No TTL: the signature is replaced only
    // when a DIFFERENT one fires, and L2Book.writeSeq being monotonic-across-reset means a re-warmed
    // book always produces a strictly-higher stamp, so explicit clearing is unnecessary.
    private final long[] lastFiredWorstPx;
    private final long[] lastFiredBaseQty;
    private final long[] lastFiredWriteSeq;
    private final boolean[] hasFiredSignature;

    // PRE-LIVE-PLAN.md P0-1: nullable, dry-run-only -- see ConsumptionLedger's javadoc. Wired via
    // setConsumptionLedger (not the constructor -- see that method's javadoc for why) so none of
    // this class's many existing constructor call sites needed to change. Kept alongside
    // edgeCalculator's own copy so evaluate() can call record() at dispatch time without needing
    // EdgeCalculator to expose its private field back out.
    private ConsumptionLedger consumptionLedger;
    // PRE-LIVE-PLAN.md P0-2(b): extends the signature above so a triangle can also be suppressed
    // when only SOME legs are unchanged -- see anyMaterialLegUnchanged's javadoc. Same flat
    // [triangleIndex*3 + leg] layout; lastFiredNanos backs the (separate, only-for-the-new-rule)
    // suppression window.
    private final long[] lastFiredTopPx;
    private final long[] lastFiredTouchQty;
    private final long[] lastFiredNanos;
    private final double duplicateMaterialFraction;
    private final long duplicateWindowNanos;

    // PRE-LIVE-PLAN.md P0-2(d): stale-leg guard -- refuse a candidate where one leg's top has been
    // frozen for staleLegFrozenNanos while another's changed within staleLegActiveNanos. Either
    // <= 0 disables the guard entirely (BotService logs a startup WARN, not a boot failure).
    private final long staleLegFrozenNanos;
    private final long staleLegActiveNanos;

    public OpportunityDetector(BookRegistry books, TriangleRegistry triangles, RiskGates riskGates,
                                Portfolio portfolio, BotMetrics metrics, EventJournal journal,
                                SpscArrayQueue<OrderIntent> orderQueue,
                                double minNetBps, double slippageBufferBps, boolean compound,
                                long rejectJournalIntervalMs) {
        this(books, triangles, riskGates, portfolio, metrics, journal, orderQueue, minNetBps,
                slippageBufferBps, compound, rejectJournalIntervalMs, 0L);
    }

    public OpportunityDetector(BookRegistry books, TriangleRegistry triangles, RiskGates riskGates,
                                Portfolio portfolio, BotMetrics metrics, EventJournal journal,
                                SpscArrayQueue<OrderIntent> orderQueue,
                                double minNetBps, double slippageBufferBps, boolean compound,
                                long rejectJournalIntervalMs, long postResetQuarantineMs) {
        this(books, triangles, riskGates, portfolio, metrics, journal, orderQueue, minNetBps,
                slippageBufferBps, compound, rejectJournalIntervalMs, postResetQuarantineMs,
                DEFAULT_DUPLICATE_MATERIAL_FRACTION, DEFAULT_DUPLICATE_WINDOW_MS);
    }

    public OpportunityDetector(BookRegistry books, TriangleRegistry triangles, RiskGates riskGates,
                                Portfolio portfolio, BotMetrics metrics, EventJournal journal,
                                SpscArrayQueue<OrderIntent> orderQueue,
                                double minNetBps, double slippageBufferBps, boolean compound,
                                long rejectJournalIntervalMs, long postResetQuarantineMs,
                                double duplicateMaterialFraction, long duplicateWindowMs) {
        this(books, triangles, riskGates, portfolio, metrics, journal, orderQueue, minNetBps,
                slippageBufferBps, compound, rejectJournalIntervalMs, postResetQuarantineMs,
                duplicateMaterialFraction, duplicateWindowMs,
                DEFAULT_STALE_LEG_FROZEN_MS, DEFAULT_STALE_LEG_ACTIVE_MS);
    }

    public OpportunityDetector(BookRegistry books, TriangleRegistry triangles, RiskGates riskGates,
                                Portfolio portfolio, BotMetrics metrics, EventJournal journal,
                                SpscArrayQueue<OrderIntent> orderQueue,
                                double minNetBps, double slippageBufferBps, boolean compound,
                                long rejectJournalIntervalMs, long postResetQuarantineMs,
                                double duplicateMaterialFraction, long duplicateWindowMs,
                                long staleLegFrozenMs, long staleLegActiveMs) {
        // PRE-LIVE-PLAN.md P0-2(b): S6 -- a misconfigured materiality gate is loud and fatal, same
        // as every other risk-bearing config key.
        if (duplicateMaterialFraction <= 0 || duplicateMaterialFraction > 1.0) {
            throw new IllegalStateException(
                    "cf-bot.detector.duplicate-material-fraction must be in (0, 1], got "
                            + duplicateMaterialFraction);
        }
        this.duplicateMaterialFraction = duplicateMaterialFraction;
        this.duplicateWindowNanos = duplicateWindowMs * 1_000_000L;
        // PRE-LIVE-PLAN.md P0-2(d): unlike the materiality gate above, an out-of-range value here is
        // a tuning knob, not a safety limit -- disable with a WARN (logged by BotService, which owns
        // the config resolution), never fail the boot.
        this.staleLegFrozenNanos = staleLegFrozenMs > 0 ? staleLegFrozenMs * 1_000_000L : 0L;
        this.staleLegActiveNanos = staleLegActiveMs > 0 ? staleLegActiveMs * 1_000_000L : 0L;
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
        this.postResetQuarantineNanos = postResetQuarantineMs * 1_000_000L;

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
        this.pendingSizeCandidates = new int[n];
        this.pendingNetBpsAtCap = new double[n];
        this.lastFiredWorstPx = new long[n * 3];
        this.lastFiredBaseQty = new long[n * 3];
        this.lastFiredWriteSeq = new long[n * 3];
        this.hasFiredSignature = new boolean[n];
        this.lastFullEvalNanos = new long[n];
        this.lastFiredTopPx = new long[n * 3];
        this.lastFiredTouchQty = new long[n * 3];
        this.lastFiredNanos = new long[n];
        // Same "0 is not a safe never-happened sentinel" reasoning as RiskGates' cooldown array:
        // nanoTime's origin is arbitrary per JVM. pendingSampledFrom==0 already means "no window
        // open", so windowStartNanos only matters once a window is open -- but seed it clear of
        // subtraction overflow anyway.
        java.util.Arrays.fill(windowStartNanos, Long.MIN_VALUE / 2);
        java.util.Arrays.fill(lastFullEvalNanos, Long.MIN_VALUE / 2);
        java.util.Arrays.fill(lastFiredNanos, Long.MIN_VALUE / 2);
    }

    /** PRE-LIVE-PLAN.md P0-1: install the consumption ledger, wiring it into this instance's own
     * {@link EdgeCalculator} too. A setter, not a constructor parameter — {@code BotService}
     * constructs the ledger only when {@code dryRun}, and this class already has enough constructor
     * overloads carrying real risk-bearing config; this one carries no config of its own to validate
     * (all of that lives in {@code ConsumptionLedger}'s own constructor). Called at most once, by
     * {@code BotService}, before the feed connects. */
    public void setConsumptionLedger(ConsumptionLedger ledger) {
        this.consumptionLedger = ledger;
        this.edgeCalculator.setConsumptionLedger(ledger);
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
        if (postResetQuarantineNanos > 0) {
            int quarantinedLeg = firstQuarantinedLeg(tri, nowNanos);
            if (quarantinedLeg >= 0) {
                // PRE-LIVE-PLAN.md P0-2(c): a leg that JUST reset can already be isTrusted() (the
                // stale-leg check above already passed) on a ladder rebuilt from a handful of
                // deltas -- nowhere near a full book. Same shape as the stale-skip branch above,
                // its own counter so an operator can tell "quarantined" from "genuinely stale".
                metrics.recordPostResetSkip(tri.symbolIndex()[quarantinedLeg]);
                return;
            }
        }
        if (staleLegFrozenNanos > 0 && staleLegActiveNanos > 0 && hasStaleLegLag(tri, nowNanos)) {
            // PRE-LIVE-PLAN.md P0-2(d): one leg's top has been frozen while another genuinely
            // moved -- the edge is lag, not a real opportunity (the 12:30:16 BTCUSDC-bid-161.58-
            // above-BTCUSDT-ask signature). Silent (counter-only), same shape as the checks above --
            // this runs before EdgeCalculator ever evaluates, so there is no per-leg depth to
            // journal yet.
            metrics.recordStaleLeg(triangleIndex);
            return;
        }

        // cf-arb-bot-review-plan.md Tier 2 step 2.7: size at min(eligible anchor balance, cap);
        // compound=false pins the eligible balance to the original seed rather than the compounding
        // equity. JOURNAL-TUNING-TASK.md T5: the cap is now per-triangle (defaults to the global
        // cf-bot.risk.max-notional-usd), so a thin cross triangle can be sized smaller than a deep
        // one -- JOURNAL-BPS-ANALYSIS.md §3.1 measured drag scaling ~linearly with size.
        long eligibleBalance = compound ? portfolio.equity() : seedFixed;
        long candidateNotional = Math.min(eligibleBalance, triangles.maxNotionalFixed(triangleIndex));
        if (!riskGates.canFire(triangleIndex, candidateNotional, nowNanos)) {
            // PRE-LIVE-PLAN.md P0-2(a): attribute the block to the window budget specifically, for
            // telemetry only -- canFire() already made the actual (fail-closed) decision above.
            if (riskGates.windowBudgetWouldBlock(triangleIndex, candidateNotional, nowNanos)) {
                metrics.recordWindowBudgetBlock(triangleIndex);
            }
            return;
        }

        // DYNAMIC-SIZING-TASK.md Phase 1: force one full (non-bailed) evaluation per triangle per
        // reject-sample window so the drag diagnostic keeps appearing; every other tick bails the
        // instant grossBps proves no size could clear the fire gate (net <= gross always).
        boolean wantDiagnostic = nowNanos - lastFullEvalNanos[triangleIndex] >= rejectJournalIntervalNanos;
        double bail = wantDiagnostic ? Double.NEGATIVE_INFINITY : (minNetBps + slippageBufferBps);
        // Phase 2: search the ladder-boundary candidate sizes up to candidateNotional (the cap) and
        // keep the one maximising absolute profit, not bps -- candidateNotional above remains only
        // the pre-check ceiling handed to riskGates.canFire.
        edgeCalculator.evaluateBestSize(tri, books, candidateNotional, edgeResult, bail, nowNanos);
        if (wantDiagnostic) {
            lastFullEvalNanos[triangleIndex] = nowNanos;
        }

        if (edgeResult.bailedEarly) {
            // Not "unfillable" -- grossBps alone already proved no size could clear the gate, so the
            // ladder walk never ran. Must not count as recordOpportunityRejectedUnfillable().
            journalReject(triangleIndex, tri.name(), false, Double.NaN, edgeResult.grossBps,
                    candidateNotional, REASON_GROSS_BAIL, nowNanos);
            return;
        }
        if (!edgeResult.fillable) {
            metrics.recordOpportunityRejectedUnfillable();
            journalReject(triangleIndex, tri.name(), false, Double.NaN, edgeResult.grossBps,
                    candidateNotional, REASON_UNFILLABLE, nowNanos);
            return; // top-of-book may have looked good, but the real ladder/lot-size can't fill it
        }
        metrics.recordOpportunityDetected();
        // DYNAMIC-SIZING-TASK.md Phase 2: the fire decision and every downstream consumer (the
        // duplicate-fire signature, OrderIntent, journal notional_usd) use the SIZE THE SEARCH
        // CHOSE, never the operator's cap -- the search can only choose <= candidateNotional (S6).
        long chosenNotional = edgeResult.legInputAmount[0];
        if (edgeResult.netBps <= minNetBps + slippageBufferBps) {
            journalReject(triangleIndex, tri.name(), true, edgeResult.netBps, edgeResult.grossBps,
                    chosenNotional, REASON_BELOW_THRESHOLD, nowNanos);
            return; // real edge doesn't clear threshold + slippage buffer
        }

        // DUPLICATE-FIRE-TASK.md ("Fix A") + PRE-LIVE-PLAN.md P0-2(b): refuse to re-fire a triangle
        // while the liquidity this order would consume is the liquidity the last fire consumed.
        // Placed AFTER the threshold test and BEFORE riskGates.claim(), matching canFire()'s
        // non-claiming pre-check contract: a suppressed duplicate must not burn cooldown or
        // rate-limit budget. In dry-run the paper fill never consumes depth, so the book keeps
        // offering the identical edge; in live mode the venue's depth push lags a fill by 14-41ms,
        // so a re-fire on the same signature prices leg 0 at a boundary that no longer exists and
        // tends to break -- and a broken cycle costs ~13x a winning one (JOURNAL-BPS-ANALYSIS.md
        // §12-15). Two independent triggers, either one suppresses:
        //   - fireSignatureUnchanged: ALL three legs identical, including the book write stamp --
        //     Fix A's original rule, with no time bound (a truly static book stays suppressed).
        //   - anyMaterialLegUnchanged: P0-2(b) -- BTCUSDT resets ~79x/h, so its write stamp keeps
        //     advancing even when nothing material changed, and "one churning leg unlocks re-fires
        //     against two frozen ones" defeated the rule above almost entirely (10 suppressions in
        //     11h, on one triangle). Catches a MATERIALLY-sized leg whose price/depth genuinely
        //     didn't move, bounded by duplicateWindowNanos so an old fire can't veto forever.
        if (hasFiredSignature[triangleIndex]
                && (fireSignatureUnchanged(triangleIndex)
                    || (withinDuplicateWindow(triangleIndex, nowNanos) && anyMaterialLegUnchanged(triangleIndex)))) {
            metrics.recordDuplicateFireSuppressed(triangleIndex);
            journalReject(triangleIndex, tri.name(), true, edgeResult.netBps, edgeResult.grossBps,
                    chosenNotional, REASON_DUPLICATE, nowNanos);
            return;
        }

        // Claim the fire slot NOW, immediately before handing off -- canFire() above is a
        // non-claiming pre-check so a candidate that fails EdgeCalculator never burns real
        // cooldown/rate-limit budget (see RiskGates.canFire's javadoc).
        riskGates.claim(triangleIndex, chosenNotional, nowNanos);
        long[] legPrices = java.util.Arrays.copyOf(edgeResult.legWorstPriceFixed, 3);
        long[] legBaseQty = java.util.Arrays.copyOf(edgeResult.legBaseQtyFixed, 3);
        OrderIntent intent = new OrderIntent(nowNanos, triangleIndex, chosenNotional, edgeResult.netBps,
                legPrices, legBaseQty);
        if (!orderQueue.offer(intent)) {
            riskGates.onCycleFinished(); // roll back the claim -- we couldn't even enqueue it
            metrics.recordOrderQueueDrop();
            journal.write(JournalEvents.opportunity(tri.name(), edgeResult.netBps, edgeResult.grossBps,
                    chosenNotional, false, "order-queue-full", 0,
                    edgeResult.legTopPriceFixed, edgeResult.legTouchQtyFixed,
                    edgeResult.legWorstPriceFixed, edgeResult.legBaseQtyFixed,
                    edgeResult.sizeCandidates, edgeResult.netBpsAtCap));
            return;
        }
        metrics.recordOpportunityFired();
        // DUPLICATE-FIRE-TASK.md: store the signature ONLY now that the intent is actually enqueued
        // -- the queue-full branch above rolled the claim back and must stay re-fireable.
        storeFireSignature(triangleIndex, nowNanos);
        // PRE-LIVE-PLAN.md P0-1: record consumption ONLY now, same condition as the signature above
        // -- a rolled-back claim must not poison the book either.
        if (consumptionLedger != null) {
            recordConsumption(tri, nowNanos);
        }
        journal.write(JournalEvents.opportunity(tri.name(), edgeResult.netBps, edgeResult.grossBps,
                chosenNotional, true, null, 0,
                edgeResult.legTopPriceFixed, edgeResult.legTouchQtyFixed,
                edgeResult.legWorstPriceFixed, edgeResult.legBaseQtyFixed,
                edgeResult.sizeCandidates, edgeResult.netBpsAtCap));
    }

    /** DUPLICATE-FIRE-TASK.md: true iff the current {@link #edgeResult} matches the stored
     * last-fired signature for {@code triangleIndex} on ALL three legs (worst price, base qty, and
     * book write stamp). Caller must have checked {@link #hasFiredSignature} first. */
    private boolean fireSignatureUnchanged(int triangleIndex) {
        int base = triangleIndex * 3;
        for (int leg = 0; leg < 3; leg++) {
            if (lastFiredWorstPx[base + leg] != edgeResult.legWorstPriceFixed[leg]
                    || lastFiredBaseQty[base + leg] != edgeResult.legBaseQtyFixed[leg]
                    || lastFiredWriteSeq[base + leg] != edgeResult.legWriteSeq[leg]) {
                return false;
            }
        }
        return true;
    }

    /** PRE-LIVE-PLAN.md P0-2(b): true iff at least one MATERIAL leg (this order's base quantity is
     * at least {@link #duplicateMaterialFraction} of that leg's available touch quantity) has the
     * same top price, the same worst (VWAP-walked) price, and a touch quantity that has NOT
     * increased since the last fire -- i.e. nobody replenished it. The materiality gate excludes a
     * deep, effectively-constant leg (USDCUSDT's top) from vetoing suppression on its own; without
     * it, a naive "any leg matches" rule over-suppresses. Caller must have checked
     * {@link #hasFiredSignature} first. */
    private boolean anyMaterialLegUnchanged(int triangleIndex) {
        int base = triangleIndex * 3;
        for (int leg = 0; leg < 3; leg++) {
            long touchQty = edgeResult.legTouchQtyFixed[leg];
            if (touchQty <= 0) {
                continue; // nothing recorded for this leg -- can't judge materiality
            }
            double materialRatio = (double) edgeResult.legBaseQtyFixed[leg] / (double) touchQty;
            if (materialRatio < duplicateMaterialFraction) {
                continue; // this leg's own share of the touch is too small to be decisive
            }
            if (lastFiredTopPx[base + leg] == edgeResult.legTopPriceFixed[leg]
                    && lastFiredWorstPx[base + leg] == edgeResult.legWorstPriceFixed[leg]
                    && edgeResult.legTouchQtyFixed[leg] <= lastFiredTouchQty[base + leg]) {
                return true;
            }
        }
        return false;
    }

    /** PRE-LIVE-PLAN.md P0-2(b): the new any-material-leg rule, unlike Fix A's original all-legs
     * rule, is bounded by a suppression window so a fire from long ago can't veto a fresh one
     * forever. */
    private boolean withinDuplicateWindow(int triangleIndex, long nowNanos) {
        return nowNanos - lastFiredNanos[triangleIndex] <= duplicateWindowNanos;
    }

    private void storeFireSignature(int triangleIndex, long nowNanos) {
        int base = triangleIndex * 3;
        for (int leg = 0; leg < 3; leg++) {
            lastFiredWorstPx[base + leg] = edgeResult.legWorstPriceFixed[leg];
            lastFiredBaseQty[base + leg] = edgeResult.legBaseQtyFixed[leg];
            lastFiredWriteSeq[base + leg] = edgeResult.legWriteSeq[leg];
            lastFiredTopPx[base + leg] = edgeResult.legTopPriceFixed[leg];
            lastFiredTouchQty[base + leg] = edgeResult.legTouchQtyFixed[leg];
        }
        lastFiredNanos[triangleIndex] = nowNanos;
        hasFiredSignature[triangleIndex] = true;
    }

    /** PRE-LIVE-PLAN.md P0-1: commits {@link #edgeResult}'s per-leg consumption breakdown (the
     * WINNING candidate {@code evaluateBestSize} chose) into {@link #consumptionLedger}. Caller must
     * have already checked {@code consumptionLedger != null} and be past the successful
     * {@code orderQueue.offer()} -- see the call site's comment. */
    private void recordConsumption(Triangle tri, long nowNanos) {
        int[] symbolIndex = tri.symbolIndex();
        Side[] sides = tri.side();
        for (int leg = 0; leg < 3; leg++) {
            int base = leg * Sizer.MAX_TRACKED_LEVELS;
            int touched = edgeResult.legLevelsTouched[leg];
            for (int t = 0; t < touched; t++) {
                consumptionLedger.record(symbolIndex[leg], sides[leg], edgeResult.legLevelIndex[base + t],
                        edgeResult.legLevelWriteSeq[base + t], edgeResult.legLevelQtyConsumed[base + t], nowNanos);
            }
        }
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

    /** A fillable (below-threshold) candidate always beats a non-fillable one — it is strictly
     * closer to firing. "Non-fillable" now covers TWO distinct reasons (DYNAMIC-SIZING-TASK.md
     * Phase 1): {@code REASON_UNFILLABLE} (the ladder walk ran and failed) and
     * {@code REASON_GROSS_BAIL} (grossBps alone already proved no size could clear the gate, so the
     * walk never ran) — both have a real {@code grossBps} and no {@code netBps}, so within the
     * non-fillable tier they rank the same way, by {@code grossBps}. Within the fillable tier, rank
     * by the metric that matters: {@code netBps}. */
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
        pendingSizeCandidates[tri] = edgeResult.sizeCandidates;
        pendingNetBpsAtCap[tri] = edgeResult.netBpsAtCap;
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
                emitLegTopPx, emitLegTouchQty, emitLegWorstPx, emitLegBaseQty,
                pendingSizeCandidates[tri], pendingNetBpsAtCap[tri]));
        pendingSampledFrom[tri] = 0;
    }

    /** Sampling-disabled path (interval 0): the current candidate is written as its own line. */
    private void emitNow(String name, boolean fillable, double netBps, double grossBps,
                         long notional, int reason, int sampledFrom) {
        journal.write(JournalEvents.opportunity(name, netBps, grossBps, notional, false,
                reasonString(reason), sampledFrom,
                edgeResult.legTopPriceFixed, edgeResult.legTouchQtyFixed,
                edgeResult.legWorstPriceFixed, edgeResult.legBaseQtyFixed,
                edgeResult.sizeCandidates, edgeResult.netBpsAtCap));
    }

    private static String reasonString(int reason) {
        return switch (reason) {
            case REASON_UNFILLABLE -> "unfillable";
            case REASON_DUPLICATE -> "duplicate-signature";
            case REASON_GROSS_BAIL -> "below-gross-ceiling";
            default -> "below-threshold";
        };
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

    /** PRE-LIVE-PLAN.md P0-2(c): returns the index (0..2) of the first leg whose book reset more
     * recently than {@link #postResetQuarantineNanos}, or -1 if every leg is clear. Only called
     * when the quarantine is enabled ({@code postResetQuarantineNanos > 0}). */
    private int firstQuarantinedLeg(Triangle tri, long nowNanos) {
        int[] symbolIndex = tri.symbolIndex();
        for (int leg = 0; leg < 3; leg++) {
            L2Book book = books.book(symbolIndex[leg]);
            if (nowNanos - book.lastResetNanos() < postResetQuarantineNanos) {
                return leg;
            }
        }
        return -1;
    }

    /** PRE-LIVE-PLAN.md P0-2(d): true iff at least one leg's top has been frozen for at least
     * {@link #staleLegFrozenNanos} while at least one (necessarily different, since the two
     * thresholds don't overlap under any sane config) leg's top changed within {@link
     * #staleLegActiveNanos} -- needs no price history, just each leg's own top-change age. Only
     * called when the guard is enabled. */
    private boolean hasStaleLegLag(Triangle tri, long nowNanos) {
        int[] symbolIndex = tri.symbolIndex();
        Side[] sides = tri.side();
        boolean anyFrozen = false;
        boolean anyActive = false;
        for (int leg = 0; leg < 3; leg++) {
            L2Book book = books.book(symbolIndex[leg]);
            long age = nowNanos - book.lastTopChangeNanos(sides[leg]);
            if (age >= staleLegFrozenNanos) {
                anyFrozen = true;
            }
            if (age <= staleLegActiveNanos) {
                anyActive = true;
            }
        }
        return anyFrozen && anyActive;
    }
}

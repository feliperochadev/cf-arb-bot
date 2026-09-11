package io.cfarb.exec;

import io.cfarb.graph.Triangle;
import io.cfarb.graph.TriangleRegistry;
import io.cfarb.journal.EventJournal;
import io.cfarb.journal.JournalEvents;
import io.cfarb.metrics.BotMetrics;
import io.cfarb.model.OrderIntent;
import io.cfarb.model.Side;
import io.cfarb.model.SymbolFilter;
import io.cfarb.risk.KillSwitch;
import io.cfarb.risk.RiskGates;
import io.cfarb.state.Portfolio;
import io.cfarb.util.FixedPoint;
import java.util.concurrent.locks.LockSupport;
import org.jboss.logging.Logger;
import org.jctools.queues.SpscArrayQueue;

/**
 * Drains {@link OrderIntent}s from the detector->executor SPSC queue on its own dedicated thread
 * (cf-arb-bot-plan.md §5.1 — the one declared detector->executor hand-off) and either simulates a
 * paper fill ({@code dry-run=true}, the S4 default) or places three SEQUENTIAL signed orders
 * ({@code dry-run=false}). MEXC has no WebSocket order-entry API, so legs cannot be parallelized —
 * leg n+1's size depends on leg n's actual fill (§5.4).
 *
 * <p>Blocking calls on THIS thread (REST round trips) are fine and expected — this is the named
 * dedicated thread the hot-path rules require for exactly this purpose (R5/Q4: never block an
 * event-loop thread; a purpose-built consumer thread is the correct place for I/O).
 *
 * <p><b>Rewritten by cf-arb-bot-review-plan.md Tier 1</b> in response to the independent review's
 * Major findings: leg 0's submitted quantity is now the Sizer-computed quantized base quantity
 * carried in {@link OrderIntent} (not re-derived from budget/price), legs 1-2 are re-quantized and
 * re-validated against venue minimums from each leg's ACTUAL proceeds (never assumed to equal the
 * modeled size), every leg is placed then unconditionally reconciled via {@link OrderReconciler}
 * (the placement response alone never carries fill data on this venue), a partial fill always aborts
 * into {@link Unwinder} rather than continuing at a reduced size, and PnL is computed from actual
 * spend/proceeds rather than against the pre-trade candidate notional.
 */
public final class CycleExecutor {

    private static final Logger LOG = Logger.getLogger(CycleExecutor.class);

    private final SpscArrayQueue<OrderIntent> queue;
    private final TriangleRegistry triangles;
    private final RiskGates riskGates;
    private final KillSwitch killSwitch;
    private final Portfolio portfolio;
    private final BotMetrics metrics;
    private final EventJournal journal;
    private final boolean dryRun;
    private final MexcOrderApi rest;        // never null -- see BotService (Tier 1 step 1.9: dry-run
                                             // signs and discards through the same client, minus keys)
    private final Unwinder unwinder;        // null when dryRun (only live mode can hold inventory)
    private final OrderReconciler reconciler; // null when dryRun
    private final long legTimeoutMs;
    private final long maxIntentAgeNanos;    // REVIEW.md MED-10

    private volatile boolean running;
    private Thread thread;

    public CycleExecutor(SpscArrayQueue<OrderIntent> queue, TriangleRegistry triangles,
                          RiskGates riskGates, KillSwitch killSwitch, Portfolio portfolio,
                          BotMetrics metrics, EventJournal journal, boolean dryRun,
                          MexcOrderApi rest, Unwinder unwinder, String orderType, long legTimeoutMs,
                          long maxIntentAgeMs) {
        this.queue = queue;
        this.triangles = triangles;
        this.riskGates = riskGates;
        this.killSwitch = killSwitch;
        this.portfolio = portfolio;
        this.metrics = metrics;
        this.journal = journal;
        this.dryRun = dryRun;
        this.rest = rest;
        this.unwinder = unwinder;
        this.legTimeoutMs = legTimeoutMs;
        this.maxIntentAgeNanos = maxIntentAgeMs * 1_000_000L;
        if (!dryRun && (rest == null || unwinder == null)) {
            throw new IllegalStateException("live execution requires a MexcRestClient and Unwinder");
        }
        this.reconciler = dryRun ? null : new OrderReconciler(rest, orderType, legTimeoutMs);
    }

    public void start() {
        running = true;
        thread = new Thread(this::runLoop, "cf-arb-executor");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    private void runLoop() {
        while (running) {
            OrderIntent intent = queue.poll();
            if (intent == null) {
                LockSupport.parkNanos(200_000); // 200µs
                continue;
            }
            if (killSwitch.tripped()) {
                // cf-arb-bot-review-plan.md Tier 1 step 1.8: a cycle can already be enqueued at the
                // moment of a trip (RiskGates.canFire's kill-switch check runs on the detector
                // thread before this executor thread ever sees the intent) -- discard rather than
                // execute it. There is no live order in flight to cancel at this point (each leg is
                // placed then reconciled to a terminal state before the next one starts), so
                // discarding the not-yet-started intent is the complete "freeze new work" action
                // available without a broader balance-reconciliation model (Tier 3).
                LOG.warnf("kill switch tripped -- discarding queued cycle for triangle index %d",
                        intent.triangleIndex());
                riskGates.onCycleFinished();
                continue;
            }
            try {
                execute(intent);
            } catch (Throwable t) {
                // cf-arb-bot-review-plan.md Tier 1 step 1.6: this used to ALSO call
                // riskGates.onCycleFinished() here, on top of execute()'s own finally block below --
                // any exception escaping execute() released the open-cycle slot twice, and a second
                // escaping exception could drive the count permanently negative, defeating
                // max-open-cycles. execute()'s finally is now the ONLY release for this cycle.
                LOG.errorf(t, "unhandled exception executing cycle for triangle index %d", intent.triangleIndex());
                killSwitch.recordFailure("executor-exception: " + t);
            }
        }
    }

    private void execute(OrderIntent intent) {
        Triangle triangle = triangles.triangle(intent.triangleIndex());
        long fullCycleStart = System.nanoTime();
        try {
            // REVIEW.md MED-10: a delay between detection and this thread actually dequeuing the
            // intent (a slow prior cycle, a REST timeout, a JVM pause) can leave an intent stale
            // enough that acting on it is close to guaranteed to fail -- triangular arbitrage
            // opportunities live roughly 50-150ms. Drop it rather than execute a near-certain
            // leg failure; the open-cycle slot this cycle claimed is still released below exactly
            // once, same as every other outcome.
            long ageNanos = fullCycleStart - intent.detectedAtNanos();
            if (ageNanos > maxIntentAgeNanos) {
                // Third-pass review finding: a dedicated event, not brokenCycle's shape with a
                // failed_leg=-1 sentinel -- see JournalEvents#intentExpired's javadoc.
                metrics.recordIntentExpired();
                journal.write(JournalEvents.intentExpired(triangle.name(), ageNanos, portfolio.equity()));
                return;
            }
            if (dryRun) {
                executePaper(triangle, intent);
            } else {
                executeLive(triangle, intent);
            }
        } finally {
            riskGates.onCycleFinished();
            metrics.recordFullCycleNanos(System.nanoTime() - fullCycleStart);
        }
    }

    /**
     * Paper fill: credit the PnL that {@code EdgeCalculator} already computed from the REAL
     * quantized/VWAP-walked ladder at detection time (not a re-estimate) — {@code intent.detectedNetBps()}
     * is the honest achievable edge, not a top-of-book approximation (cf-arb-bot-plan.md §5.3).
     * This is the S4-mandated default and never touches the network.
     *
     * <p>Also builds and signs every leg's order params through the real code path and discards
     * them (Tier 1 step 1.9 / plan §5.4 Phase 3: "the executor builds and signs every request and
     * then discards it, so serialization cost and the full latency histogram are real") — this is
     * the only network-adjacent work dry-run does, and {@link #rest} never calls {@code .send()}
     * for it.
     */
    private void executePaper(Triangle triangle, OrderIntent intent) {
        signAndDiscardForLatencyMeasurement(triangle, intent);

        long pnl = FixedPoint.mulDiv(intent.candidateNotionalFixed(),
                FixedPoint.fromDouble(intent.detectedNetBps() / 10_000.0), FixedPoint.SCALE);
        long equityAfter = portfolio.applyRealizedPnl(pnl);
        killSwitch.recordSuccess();
        killSwitch.checkEquityFloor();
        metrics.recordCycleCompleted();
        journal.write(JournalEvents.cycle(triangle.name(), intent.candidateNotionalFixed(),
                intent.detectedNetBps(), pnl, equityAfter, System.nanoTime() - intent.detectedAtNanos()));
    }

    private void signAndDiscardForLatencyMeasurement(Triangle triangle, OrderIntent intent) {
        String cycleId = "dry" + intent.detectedAtNanos();
        SymbolFilter[] filters = triangle.filter();
        Side[] sides = triangle.side();
        for (int leg = 0; leg < 3; leg++) {
            long legStart = System.nanoTime();
            String clientOrderId = cycleId + "-" + leg;
            String params = buildOrderParams(filters[leg].symbol(), sides[leg], intent.legBaseQtyFixed()[leg],
                    intent.legWorstPriceFixed()[leg], filters[leg], clientOrderId, "LIMIT");
            rest.sign(params); // built and signed; never sent -- see class/method javadoc
            metrics.recordDecisionToLeg1AckNanos(System.nanoTime() - legStart);
        }
    }

    private void executeLive(Triangle triangle, OrderIntent intent) {
        Side[] sides = triangle.side();
        SymbolFilter[] filters = triangle.filter();
        String cycleId = "c" + intent.detectedAtNanos() + "-" + intent.triangleIndex();
        CycleState state = new CycleState(cycleId);

        long carryAmount = 0; // legs 1-2 only: the previous leg's ACTUAL net proceeds
        for (int leg = 0; leg < 3; leg++) {
            SymbolFilter filter = filters[leg];
            Side side = sides[leg];
            long priceFixed = intent.legWorstPriceFixed()[leg];
            CycleState.Leg legState = state.legs[leg];

            // Third-pass review finding: record what this leg was HANDED, before it is submitted, so
            // Unwinder can carry whatever the leg fails to consume (a PARTIAL fill's remainder)
            // backwards through the reversal chain instead of abandoning it in a non-anchor asset.
            legState.inputAmountFixed = leg == 0 ? intent.candidateNotionalFixed() : carryAmount;

            if (leg == 0) {
                // Tier 1 step 1.1/1.2: submit exactly the Sizer-computed quantized base quantity,
                // never a re-derived budget/price division that discards the ladder walk.
                legState.requestedBaseQtyFixed = intent.legBaseQtyFixed()[0];
                legState.requestedPriceFixed = priceFixed;
            } else {
                // Re-quantize from the previous leg's ACTUAL proceeds and re-validate against venue
                // minimums (new defect 1) -- only leg 0's size was validated at detection time.
                long[] q = quantizeLeg(side, filter, carryAmount, priceFixed);
                if (q[0] < filter.minQty() || q[1] < filter.minNotional()) {
                    legState.status = CycleState.LegStatus.REJECTED_PRESUBMIT;
                    handleBrokenCycle(triangle, state, leg - 1, "leg-" + leg + "-below-venue-minimum");
                    return;
                }
                legState.requestedBaseQtyFixed = q[0];
                legState.requestedPriceFixed = priceFixed;
            }

            long legStart = System.nanoTime();
            reconciler.submitAndReconcile(filter.symbol(), side, filter, legState);
            metrics.recordDecisionToLeg1AckNanos(System.nanoTime() - legStart);

            switch (legState.status) {
                case UNKNOWN -> {
                    // Terra Major 4 / Grok Major 6: reconciliation could not establish a terminal
                    // state. Never guess -- no automated unwind, trip the kill switch for operator
                    // review, and leave whatever inventory may exist exactly as-is.
                    //
                    // Third-pass review finding (M1): this used to call killSwitch.recordFailure(),
                    // which only trips after cf-bot.risk.max-consecutive-failures (default 3) in a
                    // row -- contradicting CycleState.LegStatus#UNKNOWN's own javadoc ("it trips the
                    // kill switch and waits for operator review"). Between the first and third
                    // UNKNOWN, Portfolio is never debited (no unwind runs, so
                    // handleBrokenCycle/applyBrokenCyclePnl is never reached), yet the executor keeps
                    // accepting new cycles against equity that no longer reflects reality -- the
                    // account may already be holding non-anchor inventory from the FIRST UNKNOWN leg.
                    // recordUnrecoverableInventory trips on the first occurrence, exactly as the
                    // status's own contract promises and as KillSwitch already does for the
                    // structurally identical "no automated recovery path" case in Unwinder.
                    metrics.recordCycleBroken();
                    killSwitch.recordUnrecoverableInventory(
                            "leg-" + leg + "-reconciliation-unknown (" + triangle.name() + ")");
                    journal.write(JournalEvents.brokenCycle(triangle.name(), leg,
                            "reconciliation-unknown-operator-review-required", 0, portfolio.equity()));
                    return;
                }
                case REJECTED_PRESUBMIT -> {
                    // REVIEW.md MAJ-04: a definitive 4xx placement rejection is an ORDINARY broken
                    // cycle (bad filter, insufficient balance, etc.), not the ambiguous-outcome
                    // emergency UNKNOWN represents -- the venue told us outright the order was never
                    // created, so there is a clean earlier-legs-only unwind to run, and this counts
                    // toward the ordinary consecutive-failure trip rather than an immediate one.
                    handleBrokenCycle(triangle, state, leg, "rejected-presubmit");
                    return;
                }
                case ZERO_FILL -> {
                    handleBrokenCycle(triangle, state, leg, "zero-fill");
                    return;
                }
                case PARTIAL -> {
                    // cf-arb-bot-review-plan.md's partial-fill decision: always abort and unwind,
                    // never continue at the reduced size.
                    handleBrokenCycle(triangle, state, leg, "partial-fill");
                    return;
                }
                default -> {
                    // FILLED -- proceed with this leg's actual net proceeds.
                    carryAmount = OrderReconciler.netProceeds(side, filter, legState);
                }
            }
        }

        long finalAnchor = OrderReconciler.netProceeds(sides[2], filters[2], state.legs[2]);
        long anchorSpent = anchorSpent(triangle, state);
        long pnl = finalAnchor - anchorSpent;
        long equityAfter = portfolio.applyRealizedPnl(pnl);
        killSwitch.recordSuccess();
        killSwitch.checkEquityFloor();
        metrics.recordCycleCompleted();
        journal.write(JournalEvents.cycle(triangle.name(), intent.candidateNotionalFixed(),
                intent.detectedNetBps(), pnl, equityAfter, System.nanoTime() - intent.detectedAtNanos()));
    }

    private void handleBrokenCycle(Triangle triangle, CycleState state, int failedLeg, String reason) {
        metrics.recordCycleBroken();
        Unwinder.Result r = unwinder.unwind(triangle, state, failedLeg);
        long spent = anchorSpent(triangle, state);
        // Both sides anchor-denominated (Tier 1 step 1.4) -- the previous design subtracted a raw
        // held-asset amount from an anchor amount, comparing mismatched currencies (Kimi Minor 3).
        long loss = r.recoveredAnchorFixed() - spent;
        long equityAfter = loss != 0 ? portfolio.applyBrokenCyclePnl(loss) : portfolio.equity();
        // cf-arb-bot-review-plan.md (second pass) Tier A4: Unwinder.Result#unrecoverable means real
        // inventory is verifiably stranded with no automated recovery path (no pricing source AND
        // no MARKET fallback) -- that is an immediate operator-review emergency, not an ordinary
        // failure that should merely count toward the consecutive-failure trip.
        if (r.unrecoverable()) {
            killSwitch.recordUnrecoverableInventory(reason + " (leg " + failedLeg + ", " + triangle.name() + "): "
                    + r.detail());
        } else if (loss != 0) {
            killSwitch.recordFailure(reason + " (leg " + failedLeg + ", " + triangle.name() + ")");
        } else {
            // PRE-LIVE-PLAN.md P1-4(a): no real inventory moved (e.g. a leg-0 zero-fill) -- a free
            // missed trade, not a failure. At measured break rates, counting this as an ordinary
            // failure was a 49% chance of a halt on day one at zero financial cost.
            metrics.recordCycleNoFill();
            killSwitch.recordNoFill(reason + " (leg " + failedLeg + ", " + triangle.name() + ")");
        }
        killSwitch.checkEquityFloor();
        journal.write(JournalEvents.brokenCycle(triangle.name(), failedLeg, reason, loss, equityAfter));
    }

    /** The anchor amount actually given up on leg 0 -- 0 if leg 0 itself never acquired anything
     * (nothing was ever spent, so there is nothing to recover and no loss beyond the failed order
     * itself, matching the previous design's leg-0 special case, now generalized to real amounts). */
    private static long anchorSpent(Triangle triangle, CycleState state) {
        CycleState.Leg leg0 = state.legs[0];
        if (leg0.status != CycleState.LegStatus.FILLED && leg0.status != CycleState.LegStatus.PARTIAL) {
            return 0;
        }
        return triangle.side()[0] == Side.ASK ? leg0.executedQuoteFixed : leg0.executedBaseQtyFixed;
    }

    /** Quantize a re-sized leg (1 or 2) from the previous leg's actual proceeds, mirroring
     * {@code strategy.Sizer}'s rounding rule exactly -- returns {@code {baseQtyFixed, quoteFixed}}.
     * Package-private so {@link Unwinder} can size reversal orders the same way. */
    static long[] quantizeLeg(Side side, SymbolFilter filter, long amount, long priceFixed) {
        long baseQty;
        if (side == Side.ASK) {
            baseQty = FixedPoint.quantizeDown(FixedPoint.mulDiv(amount, FixedPoint.SCALE, priceFixed), filter.qtyStep());
        } else {
            baseQty = FixedPoint.quantizeDown(amount, filter.qtyStep());
        }
        long quoteAmt = FixedPoint.mulDiv(baseQty, priceFixed, FixedPoint.SCALE);
        return new long[]{baseQty, quoteAmt};
    }

    /** Build the unsigned query-string params for one leg's order at exactly the price boundary the
     * detecting EdgeCalculator ladder walk assumed (Sizer.Result#worstPriceFixed's javadoc).
     * {@code baseQtyFixed} and {@code priceFixed} must already be quantized to the symbol's real
     * step/precision -- this method only renders them, it never re-derives a quantity from a budget
     * (cf-arb-bot-review-plan.md Tier 1 step 1.2: the previous version divided a budget by the
     * boundary price here, silently discarding every ladder level but the last one Sizer walked).
     * Values are rendered as plain decimals via {@link FixedPoint#toPlainString} -- never
     * {@code double}, which can emit scientific notation MEXC's order endpoint rejects. */
    static String buildOrderParams(String symbol, Side side, long baseQtyFixed, long priceFixed,
                                    SymbolFilter filter, String clientOrderId, String orderType) {
        String sideStr = side == Side.ASK ? "BUY" : "SELL";
        String qtyStr = FixedPoint.toPlainString(baseQtyFixed, filter.qtyDecimals());
        String priceStr = FixedPoint.toPlainString(priceFixed, filter.priceDecimals());
        return "symbol=" + symbol + "&side=" + sideStr + "&type=" + orderType
                + "&quantity=" + qtyStr + "&price=" + priceStr
                + "&newClientOrderId=" + clientOrderId;
    }
}

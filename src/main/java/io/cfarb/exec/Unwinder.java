package io.cfarb.exec;

import io.cfarb.graph.Triangle;
import io.cfarb.model.Side;
import io.cfarb.model.SymbolFilter;
import io.cfarb.util.FixedPoint;
import org.jboss.logging.Logger;

/**
 * Recovers from a broken cycle by walking COMPLETED legs in reverse, back to the anchor asset —
 * cf-arb-bot-plan.md §5.4 names this "the single largest engineering risk in this build."
 *
 * <p><b>Rewritten by cf-arb-bot-review-plan.md Tier 1 step 1.4</b> after the independent review's
 * Major finding: the previous design reversed the FAILED leg's symbol using the amount that was
 * being SENT INTO that leg — for a leg-0 failure, that is the anchor seed itself, submitted as if it
 * were the failed leg's base/quote asset (e.g. treating 100 USDT as 100 BTC and submitting
 * {@code SELL 100 BTC}). A failed leg was never reversed, but the wrong one.
 *
 * <p><b>Correct model:</b> in this bot's strictly sequential leg-by-leg execution (abort on the
 * first non-full fill), at most ONE leg's output is ever "in hand and not yet converted forward" at
 * the moment of failure — every earlier leg's output was already fully spent as the next leg's
 * input. That held leg is the largest index {@code <= failedLegIndex} whose status is
 * {@code FILLED} or {@code PARTIAL}:
 * <ul>
 *   <li>None exists (leg 0 itself never acquired anything) — nothing to unwind, nothing was spent.</li>
 *   <li>It is leg 2 — its own proceeds ARE already anchor-denominated (leg 2 always returns to the
 *       anchor by construction, cf-arb-bot-review-plan.md's triangle-closure validation); no order
 *       is placed, the held amount is simply booked as recovered.</li>
 *   <li>Otherwise, reverse that leg's symbol (opposite side, sized to what was actually acquired),
 *       then — since reversing leg {@code i} only returns to {@code fromAsset[i]}, not necessarily
 *       the anchor — continue reversing leg {@code i-1}, {@code i-2}, ... down to leg 0, each step
 *       using the PREVIOUS reversal's actual proceeds. After leg 0's reversal, the amount held is,
 *       by the triangle-closure invariant, anchor-denominated.</li>
 * </ul>
 *
 * <p>Reversal pricing still uses each leg's own stale detection-time price boundary (the same
 * ladder-walk price that leg originally requested) — pricing a reversal from a fresh top-of-book is
 * deferred to Tier 3 (it requires either a published book snapshot or a REST book fetch, neither of
 * which exists yet on the executor thread per CLAUDE.md's threading rules). This is a known,
 * documented limitation, not an oversight: a book that has moved adversely since detection can still
 * cause a reversal to fail or fill worse than expected, in which case the walk stops where it is and
 * escalates rather than guessing further.
 */
public final class Unwinder {

    private static final Logger LOG = Logger.getLogger(Unwinder.class);

    private final OrderReconciler reconciler;

    public Unwinder(MexcOrderApi rest, String orderType, long legTimeoutMs) {
        this.reconciler = new OrderReconciler(rest, orderType, legTimeoutMs);
    }

    /** Result of an unwind attempt. {@code recoveredAnchorFixed} is always anchor-denominated. */
    public record Result(boolean anyOrderPlaced, long recoveredAnchorFixed, String detail) {
    }

    public Result unwind(Triangle triangle, CycleState state, int failedLegIndex) {
        int heldLegIndex = -1;
        for (int i = failedLegIndex; i >= 0; i--) {
            CycleState.LegStatus status = state.legs[i].status;
            if (status == CycleState.LegStatus.FILLED || status == CycleState.LegStatus.PARTIAL) {
                heldLegIndex = i;
                break;
            }
        }

        if (heldLegIndex < 0) {
            return new Result(false, 0, "nothing acquired -- no unwind needed");
        }

        Side[] sides = triangle.side();
        SymbolFilter[] filters = triangle.filter();
        long currentAmount = OrderReconciler.netProceeds(sides[heldLegIndex], filters[heldLegIndex],
                state.legs[heldLegIndex]);

        if (heldLegIndex == 2) {
            // Leg 2 always returns to the anchor by construction (triangle-closure validation in
            // TriangleRegistry) -- its own proceeds ARE the recovered anchor amount; nothing to
            // reverse.
            LOG.warnf("[unwind] triangle=%s heldLeg=2 already anchor-denominated, recoveredAnchor=%.8f",
                    triangle.name(), FixedPoint.toDouble(currentAmount));
            return new Result(false, currentAmount, "leg 2 proceeds already anchor-denominated");
        }

        boolean anyOrderPlaced = false;
        for (int i = heldLegIndex; i >= 0; i--) {
            Side reverseSide = sides[i] == Side.BID ? Side.ASK : Side.BID;
            SymbolFilter filter = filters[i];
            long priceFixed = state.legs[i].requestedPriceFixed;

            long[] q = CycleExecutor.quantizeLeg(reverseSide, filter, currentAmount, priceFixed);
            long baseQty = q[0];
            long quoteAmt = q[1];
            if (baseQty < filter.minQty() || quoteAmt < filter.minNotional()) {
                LOG.errorf("[unwind] triangle=%s leg=%d symbol=%s: held amount %.8f is below the venue "
                                + "minimum to reverse -- STRANDED, operator review required",
                        triangle.name(), i, filter.symbol(), FixedPoint.toDouble(currentAmount));
                return new Result(anyOrderPlaced, 0,
                        "stranded below venue minimum reversing leg " + i + " on " + filter.symbol());
            }

            String clientOrderId = state.legs[i].clientOrderId + "-unwind";
            CycleState.Leg reverseLeg = new CycleState.Leg(clientOrderId);
            reverseLeg.requestedBaseQtyFixed = baseQty;
            reverseLeg.requestedPriceFixed = priceFixed;

            reconciler.submitAndReconcile(filter.symbol(), reverseSide, filter, reverseLeg);
            anyOrderPlaced = true;

            if (reverseLeg.status != CycleState.LegStatus.FILLED
                    && reverseLeg.status != CycleState.LegStatus.PARTIAL) {
                LOG.errorf("[unwind] triangle=%s leg=%d symbol=%s reversal did not fill (status=%s) -- "
                                + "%.8f of %s remains STRANDED, operator review required",
                        triangle.name(), i, filter.symbol(), reverseLeg.status,
                        FixedPoint.toDouble(currentAmount), triangle.toAsset()[i]);
                return new Result(anyOrderPlaced, 0,
                        "reversal did not fill on leg " + i + " (" + reverseLeg.status + ")");
            }

            currentAmount = OrderReconciler.netProceeds(reverseSide, filter, reverseLeg);
            LOG.warnf("[unwind] triangle=%s leg=%d symbol=%s reverseSide=%s recovered=%.8f %s",
                    triangle.name(), i, filter.symbol(), reverseSide, FixedPoint.toDouble(currentAmount),
                    triangle.fromAsset()[i]);
        }

        // After leg 0's reversal, currentAmount is anchor-denominated by the triangle-closure
        // invariant (fromAsset[0] == anchor, validated at startup).
        return new Result(anyOrderPlaced, currentAmount, "reversed legs " + heldLegIndex + "..0");
    }
}

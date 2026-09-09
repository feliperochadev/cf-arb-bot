package io.cfarb.exec;

/**
 * Executor-thread-owned record of one triangle attempt's actual (not modeled) execution, per leg:
 * client order id, what was requested, what the venue actually reports as executed, and the
 * commission charged. Added by cf-arb-bot-review-plan.md Tier 1 step 1.4/1.5 to replace the
 * previous design, which derived a single {@code amount} scalar from the immediate order-placement
 * response and could not distinguish "leg never filled" from "leg partially filled" from "leg fully
 * filled at a different quantity than requested" — the ambiguity behind the independent review's
 * Major finding that {@link Unwinder} could reverse a leg that never executed.
 *
 * <p><b>Wire-contract note (discovered building this, not in the original review):</b> MEXC's
 * {@code POST /api/v3/order} response does NOT include {@code executedQty}/{@code cummulativeQuoteQty}
 * — only {@code symbol,orderId,orderListId,price,origQty,type,side,transactTime}. Fill quantity
 * comes from a follow-up {@code GET /api/v3/order} (status/executedQty/cummulativeQuoteQty), and
 * commission detail comes from a further {@code GET /api/v3/myTrades} (commission/commissionAsset
 * per fill) — confirmed against MEXC's published spot v3 API reference 2026-09-07. Every leg
 * therefore does a place-then-reconcile round trip unconditionally, not only on timeout.
 *
 * <p>Not thread-safe by design: allocated and mutated only on the {@code cf-arb-executor} thread,
 * one instance per cycle attempt.
 */
public final class CycleState {

    public enum LegStatus {
        /** Not yet attempted. */
        PENDING,
        /** executedBaseQtyFixed >= requestedBaseQtyFixed (allowing for venue rounding). */
        FILLED,
        /** 0 < executedBaseQtyFixed < requestedBaseQtyFixed -- cf-arb-bot-review-plan.md's partial-fill
         * decision: this always aborts the cycle into Unwinder, it is never continued. */
        PARTIAL,
        /** executedBaseQtyFixed == 0 -- the order was placed but never executed (rejected,
         * canceled, or timed out with nothing filled). */
        ZERO_FILL,
        /** The leg was never submitted because pre-submit quantization/minimum validation failed
         * (cf-arb-bot-review-plan.md new defect 1: legs 1-2 must be re-validated against venue
         * minimums before submission, not just leg 0 at detection time). */
        REJECTED_PRESUBMIT,
        /** Reconciliation (GET /api/v3/order by client id) could not establish a terminal state
         * within the leg timeout budget -- the venue's true state is UNKNOWN. This is the one
         * status Unwinder must never guess past; it trips the kill switch and waits for operator
         * review rather than assuming either outcome. */
        UNKNOWN
    }

    public static final class Leg {
        public final String clientOrderId;
        /** How much of this leg's FROM-asset was handed to it -- the anchor budget for leg 0, the
         * previous leg's actual net proceeds for legs 1-2 (1e8-fixed, in from-asset units).
         *
         * <p>Third-pass review finding: without this, {@link Unwinder} had no way to know how much
         * of a leg's input the leg failed to consume. A PARTIAL fill spends only part of what it was
         * handed, and the remainder — still sitting in the from-asset, still real — was neither
         * reversed nor flagged, while {@code CycleExecutor#handleBrokenCycle} booked it as a 100%
         * loss. {@code Unwinder} now carries {@code inputAmountFixed - executed} backwards through
         * the reversal chain. Populated for every leg BEFORE submission, so it is meaningful even
         * for a leg that never reached the venue. */
        public long inputAmountFixed;
        public long requestedBaseQtyFixed;
        public long requestedPriceFixed;
        public long executedBaseQtyFixed;
        public long executedQuoteFixed;
        /** Commission asset/amount from GET /api/v3/myTrades, when that reconciliation call
         * succeeded; null/0 otherwise (see {@link #commissionEstimated}). */
        public String commissionAsset;
        public long commissionFixed;
        /** The venue's own {@code status} field from the last successful {@code GET /api/v3/order}
         * reconciliation (e.g. {@code NEW}, {@code PARTIALLY_FILLED}, {@code FILLED},
         * {@code CANCELED}) -- REVIEW.md MAJ-03: {@link OrderReconciler} cancels a non-terminal
         * order (NEW/PARTIALLY_FILLED) before treating the leg as done, rather than inferring
         * "done" purely from comparing quantities. Null until the first successful reconciliation. */
        public String venueStatus;
        /** The venue-assigned {@code orderId} from the last successful reconciliation -- used for
         * the cancel/re-query round trip and the commission lookup; may already be known from the
         * placement response even before the first query succeeds. */
        public String venueOrderId;
        /** True when {@link #commissionFixed} was estimated via {@code SymbolFilter#takerBps()}
         * rather than read from an actual fill -- honest bookkeeping per cf-arb-bot-review-plan.md
         * Tier 1 step 1.5: never silently pretend an estimate is a confirmed commission. */
        public boolean commissionEstimated;
        public LegStatus status = LegStatus.PENDING;

        public Leg(String clientOrderId) {
            this.clientOrderId = clientOrderId;
        }
    }

    public final Leg[] legs;

    public CycleState(String cycleId) {
        this.legs = new Leg[] {
                new Leg(cycleId + "-0"),
                new Leg(cycleId + "-1"),
                new Leg(cycleId + "-2"),
        };
    }
}

package io.cfarb.model;

/**
 * One fire decision, handed from the detector thread to the dedicated executor thread via a
 * JCTools SPSC ring (cf-arb-bot-plan.md §5.1's single declared detector->executor hand-off).
 * Allocated only on the rare "we're actually firing" branch (rule R1's justified exception) —
 * the common case (a tick that doesn't clear every gate) never reaches this.
 */
public record OrderIntent(
        long detectedAtNanos,
        int triangleIndex,
        long candidateNotionalFixed,
        double detectedNetBps,
        /** Per-leg worst ladder price the detecting EdgeCalculator walk touched (1e8-fixed) —
         * exec.CycleExecutor requests each leg's IOC order at exactly this price boundary, so a
         * book that has moved unfavorably since detection produces a partial/no fill (handled by
         * exec.Unwinder) rather than a worse-than-computed fill. Index 0..2 = leg 0..2. */
        long[] legWorstPriceFixed,
        /** Per-leg quantized BASE order quantity the detecting Sizer walk computed (1e8-fixed) --
         * cf-arb-bot-review-plan.md Tier 1 step 1.1: exec.CycleExecutor submits this value for leg
         * 0 verbatim (legs 1-2 re-derive from the previous leg's actual proceeds, since leg n+1's
         * size depends on leg n's real fill, but must land on this same quantization). Index 0..2 =
         * leg 0..2. */
        long[] legBaseQtyFixed) {
}

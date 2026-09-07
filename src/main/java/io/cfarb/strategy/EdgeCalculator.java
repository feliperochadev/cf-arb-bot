package io.cfarb.strategy;

import io.cfarb.book.BookRegistry;
import io.cfarb.book.L2Book;
import io.cfarb.graph.Triangle;
import io.cfarb.util.FixedPoint;

/**
 * Computes the ACTUAL achievable net edge of a triangle at a given starting capital — walking each
 * leg's real ladder, quantizing to the exchange's real lot size, and applying the real per-symbol
 * taker fee (cf-arb-bot-plan.md §5.3). This is the single piece the plan calls out as needing to be
 * "exactly right": untradeable triangles (e.g. anything sized through {@code SOLBTC} at a $100
 * seed) fall out of this arithmetic naturally — no cycle needs to be blacklisted by hand, and no
 * separate "is this even fillable" pre-check is needed, because {@link Sizer#fillLeg} already
 * rejects unfillable legs.
 *
 * <p>Cross-checked against the Python research pipeline's {@code cfarb.stage2_cycles.evaluate_cycle}
 * model in {@code EdgeCalculatorTest} using shared synthetic book fixtures (cf-arb-bot-plan.md §11
 * verification item 3) — both models must agree on {@code net_bps} to within rounding for the same
 * inputs, which is what makes this bot's live opportunity detection provably the same model the
 * $100 → $607.91 (later found unreachable — see cf-arb-bot-plan.md §2.1) and $100 → $224.45 headline
 * numbers came from.
 *
 * <p>Zero allocation on the hot path: {@link Result} and the internal {@link Sizer.Result} are
 * reusable output parameters, allocated once per {@code OpportunityDetector} instance and passed by
 * reference (rule R1).
 */
public final class EdgeCalculator {

    private final Sizer.Result legResult = new Sizer.Result();

    /** Reusable output of one full-triangle evaluation. */
    public static final class Result {
        public boolean fillable;
        /** Final amount in the anchor asset's native units after all 3 legs, fees, and quantization. */
        public long finalAmount;
        /** (finalAmount / startAmount - 1) * 10_000, or Double.NaN if unfillable. */
        public double netBps;
        /** Per-leg intended amount INTO that leg (native units of that leg's from_asset) and the
         * worst ladder price the VWAP walk touched -- exec.CycleExecutor uses these to place each
         * leg's IOC order at exactly the price boundary this calculation assumed (see
         * Sizer.Result#worstPriceFixed's javadoc). Index 0..2 = leg 0..2. */
        public final long[] legInputAmount = new long[3];
        public final long[] legWorstPriceFixed = new long[3];
        /** Per-leg quantized BASE order quantity Sizer actually computed (1e8-fixed) --
         * cf-arb-bot-review-plan.md Tier 1 step 1.1: exec.CycleExecutor must submit exactly this,
         * not re-derive a (smaller) quantity from budget/worstPrice. */
        public final long[] legBaseQtyFixed = new long[3];
        /** Per-leg quote-asset amount Sizer actually computed for that leg (1e8-fixed). */
        public final long[] legQuoteFixed = new long[3];
    }

    /**
     * Evaluate {@code triangle} starting from {@code startAmount} units (1e8-fixed) of the anchor
     * asset. Every leg must independently clear its exchange minimum and have enough displayed
     * depth to absorb the FULL intended size (Sizer's stricter-than-backtest policy) or the whole
     * triangle is marked unfillable — a triangle is only as good as its worst leg.
     */
    public void evaluate(Triangle triangle, BookRegistry books, long startAmount, Result out) {
        out.fillable = false;
        out.finalAmount = 0;
        out.netBps = Double.NaN;

        long amount = startAmount;
        int[] symbolIndex = triangle.symbolIndex();
        for (int leg = 0; leg < 3; leg++) {
            L2Book book = books.book(symbolIndex[leg]);
            if (!book.isTrusted() || book.isCrossed() || book.isEmpty()) {
                return; // caller's staleness/health gates should already have screened this, but
                        // evaluate() must never fabricate an edge from a book that isn't ready
            }
            out.legInputAmount[leg] = amount;
            Sizer.fillLeg(book, triangle.side()[leg], triangle.filter()[leg], amount, legResult);
            if (!legResult.filled) {
                return; // this leg is unfillable at this size — e.g. below SOLBTC's 1-SOL minimum
            }
            out.legWorstPriceFixed[leg] = legResult.worstPriceFixed;
            out.legBaseQtyFixed[leg] = legResult.baseQtyFixed;
            out.legQuoteFixed[leg] = legResult.quoteFixed;
            amount = legResult.outputAmount;
        }

        out.fillable = true;
        out.finalAmount = amount;
        out.netBps = (FixedPoint.toDouble(amount) / FixedPoint.toDouble(startAmount) - 1.0) * 10_000.0;
    }
}

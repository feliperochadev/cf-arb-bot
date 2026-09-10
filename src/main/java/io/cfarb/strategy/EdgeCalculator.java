package io.cfarb.strategy;

import io.cfarb.book.BookRegistry;
import io.cfarb.book.L2Book;
import io.cfarb.graph.Triangle;
import io.cfarb.model.Side;
import io.cfarb.model.SymbolFilter;
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
 *
 * <p><b>REVIEW.md MED-03</b> ("Sizer.fillAsk overstates quote notional on multi-level walks"):
 * verified NOT a defect. {@code Sizer.Result#quoteFixed} is {@code baseFilled x worstPrice}, which
 * is exactly the notional MEXC's own min-notional filter evaluates for a limit order priced at that
 * boundary -- it is the correct value for its one real use (Sizer's own {@code minNotional} check).
 * The finding also claimed downstream modules "misstate the expected spend" from this value; in
 * fact nothing downstream ever read it -- {@link Result} previously carried a {@code legQuoteFixed[]}
 * array populated from this same field and never consumed anywhere. Removed as dead code rather
 * than "fixing" an already-correct number.
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
        /** Top-of-book cyclic edge in bps BEFORE any depth limit or lot-size quantization:
         * {@code (Π (legRate_i * (1 - fee_i)) - 1) * 10_000}, the exact quantity the Python research
         * pipeline's {@code cfarb.stage2_cycles.evaluate_cycle} reports as its per-tick {@code net_bps}
         * ("opportunity" gate). Populated whenever all three books are usable — including when
         * {@link #fillable} is {@code false}, so a dry-run journal line distinguishes "no edge" from
         * "edge present but un-fillable at this size". {@code Double.NaN} if any leg's book was
         * untrusted / empty / crossed. */
        public double grossBps;
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
        out.grossBps = Double.NaN;

        int[] symbolIndex = triangle.symbolIndex();
        Side[] sides = triangle.side();
        SymbolFilter[] filters = triangle.filter();

        // First pass: top-of-book gross edge — no depth limit, no quantization, just the log-space
        // rate product cf-arb-poc's stage2_cycles.evaluate_cycle uses. Cheap (3 divisions, zero
        // allocation); left NaN if any leg's book isn't usable. This is a diagnostic surfaced on the
        // journal's opportunity events, NOT an input to the fire decision — that stays netBps below.
        double grossProduct = 1.0;
        for (int leg = 0; leg < 3; leg++) {
            L2Book book = books.book(symbolIndex[leg]);
            if (!book.isTrusted() || book.isCrossed() || book.isEmpty()) {
                return; // caller's staleness/health gates should already have screened this, but
                        // evaluate() must never fabricate an edge from a book that isn't ready
            }
            double px = FixedPoint.toDouble(
                    sides[leg] == Side.ASK ? book.bestAskPx() : book.bestBidPx());
            double legRate = (sides[leg] == Side.ASK ? 1.0 / px : px)
                    * FixedPoint.toDouble(filters[leg].takerFeeMultiplierFixed());
            grossProduct *= legRate;
        }
        out.grossBps = (grossProduct - 1.0) * 10_000.0;

        // Second pass: the real achievable edge — VWAP-walk each leg's ladder to size, quantize to
        // the exchange lot step, apply fees. This is what a fire decision uses.
        long amount = startAmount;
        for (int leg = 0; leg < 3; leg++) {
            L2Book book = books.book(symbolIndex[leg]);
            out.legInputAmount[leg] = amount;
            Sizer.fillLeg(book, sides[leg], filters[leg], amount, legResult);
            if (!legResult.filled) {
                return; // this leg is unfillable at this size — e.g. below SOLBTC's 1-SOL minimum
            }
            out.legWorstPriceFixed[leg] = legResult.worstPriceFixed;
            out.legBaseQtyFixed[leg] = legResult.baseQtyFixed;
            amount = legResult.outputAmount;
        }

        out.fillable = true;
        out.finalAmount = amount;
        out.netBps = (FixedPoint.toDouble(amount) / FixedPoint.toDouble(startAmount) - 1.0) * 10_000.0;
    }
}

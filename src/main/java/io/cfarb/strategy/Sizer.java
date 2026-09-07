package io.cfarb.strategy;

import io.cfarb.book.L2Book;
import io.cfarb.model.SymbolFilter;
import io.cfarb.model.Side;
import io.cfarb.util.FixedPoint;

/**
 * VWAP-walks one leg's captured ladder to the intended size, quantizes to the exchange's real
 * lot-size step, rejects below the minimum order quantity/notional, and applies the real per-symbol
 * taker fee — the exact arithmetic from cf-arb-bot-plan.md §5.3's {@code EdgeCalculator} pseudocode,
 * factored into its own class per the file layout in §5. Ported from
 * {@code cf-arb-poc/cfarb/gate0_rebaseline.py}'s {@code _walk_ladder_notional_limited} +
 * {@code quantized_fill_native}, operating on {@link L2Book}'s sorted arrays instead of JSON ladder
 * strings.
 *
 * <p>Never allocates: {@link Result} is a reusable output parameter, one instance per triangle
 * evaluation, mutated in place (rule R1).
 */
public final class Sizer {

    private Sizer() {
    }

    /** Reusable output of one leg fill. Allocate once, pass by reference. */
    public static final class Result {
        public boolean filled;
        /** The new amount held, in the OUTPUT asset's native 1e8-fixed units. */
        public long outputAmount;
        /** The worst (last-consumed) ladder price touched by the VWAP walk -- this is the
         * marketable IOC limit price {@code exec.CycleExecutor} must request to reproduce (at
         * best) the same fill this calculation assumed. Requesting IOC at exactly this boundary
         * means a book that has moved unfavorably since detection produces a partial fill or no
         * fill (handled by {@code exec.Unwinder}), never a worse-than-computed fill. */
        public long worstPriceFixed;
        /** The quantized BASE quantity this leg actually orders, in 1e8-fixed base-asset units --
         * cf-arb-bot-review-plan.md Tier 1 step 1.1: the executor must submit exactly this value,
         * not re-derive a smaller one from {@code budget / worstPrice} (which discards every level
         * of the ladder walk except the last). */
        public long baseQtyFixed;
        /** The quote-asset amount this leg actually spends (ASK) or receives before fees (BID), in
         * 1e8-fixed quote-asset units -- kept alongside {@link #baseQtyFixed} so the executor never
         * needs to re-derive one from the other via a boundary-price division. */
        public long quoteFixed;
    }

    /**
     * Fill one leg. {@code inputAmount} is in the CURRENT held asset's native fixed-point units
     * (quote units for an ASK leg being bought, base units for a BID leg being sold).
     *
     * <p>Deliberately STRICTER than the offline Python backtest
     * ({@code gate0_rebaseline.quantized_fill_native}), which silently accepts a partial ladder
     * fill as "whatever actually happened" for historical PnL accounting. Here, a pre-trade
     * candidate that the displayed ladder cannot fill IN FULL is rejected outright rather than
     * sized down to what's available — an IOC order sized to more than the book can absorb is
     * exactly the leg-failure scenario cf-arb-bot-plan.md §5.4 names as "the single largest
     * engineering risk in this build," so refusing to fire on a candidate we already know the book
     * can't fully support is strictly safer than discovering that mid-cycle via {@code Unwinder}.
     */
    public static void fillLeg(L2Book book, Side side, SymbolFilter filter, long inputAmount, Result out) {
        out.filled = false;
        out.outputAmount = 0;
        out.worstPriceFixed = 0;
        out.baseQtyFixed = 0;
        out.quoteFixed = 0;
        if (inputAmount <= 0) {
            return;
        }
        if (side == Side.ASK) {
            fillAsk(book, filter, inputAmount, out);
        } else {
            fillBid(book, filter, inputAmount, out);
        }
    }

    /** Buying base with quote: inputAmount is QUOTE budget; walk asks ascending (best-first). */
    private static void fillAsk(L2Book book, SymbolFilter filter, long quoteBudget, Result out) {
        long baseFilled = 0;
        long quoteSpent = 0;
        long worstPrice = 0;
        int n = book.askLevelCount();
        for (int i = 0; i < n && quoteSpent < quoteBudget; i++) {
            long price = book.askPxAt(i);
            long qty = book.askQtyAt(i);
            long levelNotional = FixedPoint.mulDiv(price, qty, FixedPoint.SCALE);
            long remainingBudget = quoteBudget - quoteSpent;
            if (levelNotional <= remainingBudget) {
                baseFilled += qty;
                quoteSpent += levelNotional;
            } else {
                baseFilled += FixedPoint.mulDiv(remainingBudget, FixedPoint.SCALE, price);
                quoteSpent = quoteBudget;
            }
            worstPrice = price;
        }
        if (quoteSpent < quoteBudget) {
            return; // the displayed ladder can't fully absorb the intended size — reject, don't partial-fill
        }
        // Quantize FIRST, then validate both minimums against the FINAL quantized size (Terra
        // Minor 1: the review found minNotional was checked against pre-rounding quoteSpent, so a
        // quantity that rounds down below the venue's real minimum notional could still pass here
        // and be submitted below the exchange's actual floor).
        baseFilled = FixedPoint.quantizeDown(baseFilled, filter.qtyStep());
        if (baseFilled < filter.minQty()) {
            return; // unfillable at this size — e.g. SOLBTC's 1-whole-SOL minimum at a $100 seed
        }
        long actualQuoteNotional = FixedPoint.mulDiv(baseFilled, worstPrice, FixedPoint.SCALE);
        if (actualQuoteNotional < filter.minNotional()) {
            return;
        }
        out.worstPriceFixed = worstPrice;
        out.baseQtyFixed = baseFilled;
        out.quoteFixed = actualQuoteNotional;
        out.outputAmount = FixedPoint.mulDiv(baseFilled, filter.takerFeeMultiplierFixed(), FixedPoint.SCALE);
        out.filled = true;
    }

    /** Selling base for quote: inputAmount is BASE held; walk bids descending (best-first). */
    private static void fillBid(L2Book book, SymbolFilter filter, long baseHeld, Result out) {
        long qtyToSell = FixedPoint.quantizeDown(baseHeld, filter.qtyStep());
        if (qtyToSell < filter.minQty()) {
            return;
        }
        long sold = 0;
        long quoteReceived = 0;
        long worstPrice = 0;
        int n = book.bidLevelCount();
        for (int i = 0; i < n && sold < qtyToSell; i++) {
            long price = book.bidPxAt(i);
            long qty = book.bidQtyAt(i);
            long remaining = qtyToSell - sold;
            long take = Math.min(qty, remaining);
            sold += take;
            quoteReceived += FixedPoint.mulDiv(price, take, FixedPoint.SCALE);
            worstPrice = price;
        }
        if (sold < qtyToSell || quoteReceived < filter.minNotional()) {
            return; // insufficient displayed depth to fill the intended (quantized) size
        }
        out.worstPriceFixed = worstPrice;
        out.baseQtyFixed = qtyToSell;
        out.quoteFixed = quoteReceived;
        out.outputAmount = FixedPoint.mulDiv(quoteReceived, filter.takerFeeMultiplierFixed(), FixedPoint.SCALE);
        out.filled = true;
    }
}

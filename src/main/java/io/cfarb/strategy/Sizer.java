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
        /** DUPLICATE-FIRE-TASK.md ("Fix A"): the highest {@link L2Book} write stamp across EVERY
         * ladder level this walk consumed -- not just the worst. A rewrite at any consumed depth
         * must invalidate a fire signature, so the detector compares this alongside
         * {@link #worstPriceFixed} / {@link #baseQtyFixed} to decide "is the liquidity I am about to
         * trade the liquidity I just traded". */
        public long maxWriteSeq;
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
        out.maxWriteSeq = 0;
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
        long maxWriteSeq = 0;
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
            // DUPLICATE-FIRE-TASK.md: one long compare-and-store per level already being visited.
            maxWriteSeq = Math.max(maxWriteSeq, book.askWriteSeqAt(i));
        }
        if (quoteSpent < quoteBudget) {
            return; // the displayed ladder can't fully absorb the intended size — reject, don't partial-fill
        }
        // Third-pass review finding: baseFilled was accumulated by walking each level at ITS OWN
        // (better-or-equal) price, so baseFilled * worstPrice can exceed quoteBudget whenever more
        // than one level was touched -- yet exec.CycleExecutor submits a SINGLE IOC limit order for
        // the full baseFilled quantity AT worstPrice (Result#worstPriceFixed's own javadoc: "the
        // marketable IOC limit price CycleExecutor must request"). MEXC's balance check evaluates the
        // order at ITS OWN limit price, not this multi-level VWAP, so a candidate sized near the full
        // available balance could be rejected for insufficient balance even though the true VWAP
        // spend modeled above fits -- max-notional-usd is meant to be a hard cap on what gets
        // reserved, not merely on what this walk projects would be spent. Cap the quantity so the
        // WORST-PRICE notional also never exceeds the budget; conservative (never orders MORE than
        // modeled) and a no-op whenever a single level satisfies the whole size (the common case,
        // where worstPrice IS the only price touched).
        long worstPriceCappedBase = FixedPoint.mulDiv(quoteBudget, FixedPoint.SCALE, worstPrice);
        baseFilled = Math.min(baseFilled, worstPriceCappedBase);
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
        out.maxWriteSeq = maxWriteSeq;
        out.outputAmount = FixedPoint.mulDiv(baseFilled, filter.takerFeeMultiplierFixed(), FixedPoint.SCALE);
        out.filled = true;
    }

    /**
     * DYNAMIC-SIZING-TASK.md Phase 2: cumulative input amounts at each ladder-level boundary of
     * leg 0's book, best-first, ascending, each clamped to {@code maxInput}, plus {@code maxInput}
     * itself as the final entry -- the candidate sizes {@code EdgeCalculator#evaluateBestSize}
     * enumerates. {@code profit(N) = finalAmount(N) - N} is concave in the depth dimension (deeper
     * levels price worse), so its maximum sits at one of these boundaries.
     *
     * <p>{@code ASK} (spending quote, buying base): cumulative {@code Σ askPxAt(i) * askQtyAt(i)} --
     * a notional. {@code BID} (spending base, selling base): cumulative {@code Σ bidQtyAt(i)} -- a
     * quantity. Today every configured triangle's leg 0 is ASK (the USDT anchor is the quote of
     * every leg-0 symbol), but {@code Triangle} does not guarantee it and a USDC-anchored cycle
     * would break that assumption, so both sides are handled.
     *
     * <p>Writes at most {@code out.length} candidates (reserving the final slot for {@code
     * maxInput} itself, which is ALWAYS included, even when the book's own depth never reaches it —
     * {@code Sizer#fillLeg} rejects that candidate on its own merits, same as any other unfillable
     * size). Stops early, without emitting the boundary itself, the moment a level's cumulative
     * would reach or exceed {@code maxInput} (that boundary is not a distinct candidate from the cap
     * -- it collapses into the {@code maxInput} entry, which is how duplicates are avoided). Levels
     * with a non-positive amount are skipped. Returns the count written; {@code 0} if
     * {@code maxInput <= 0} or {@code out.length == 0}.
     */
    public static int candidateInputs(L2Book book, Side side, long maxInput, long[] out) {
        if (maxInput <= 0 || out.length == 0) {
            return 0;
        }
        int limit = out.length - 1; // reserve the last slot for maxInput itself
        int n = 0;
        long cumulative = 0;
        int levelCount = side == Side.ASK ? book.askLevelCount() : book.bidLevelCount();
        for (int i = 0; i < levelCount && n < limit; i++) {
            long levelAmount = side == Side.ASK
                    ? FixedPoint.mulDiv(book.askPxAt(i), book.askQtyAt(i), FixedPoint.SCALE)
                    : book.bidQtyAt(i);
            if (levelAmount <= 0) {
                continue;
            }
            cumulative += levelAmount;
            if (cumulative >= maxInput) {
                break; // this boundary collapses into the maxInput entry appended below
            }
            out[n++] = cumulative;
        }
        if (n == 0 || out[n - 1] != maxInput) {
            out[n++] = maxInput;
        }
        return n;
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
        long maxWriteSeq = 0;
        int n = book.bidLevelCount();
        for (int i = 0; i < n && sold < qtyToSell; i++) {
            long price = book.bidPxAt(i);
            long qty = book.bidQtyAt(i);
            long remaining = qtyToSell - sold;
            long take = Math.min(qty, remaining);
            sold += take;
            quoteReceived += FixedPoint.mulDiv(price, take, FixedPoint.SCALE);
            worstPrice = price;
            // DUPLICATE-FIRE-TASK.md: track the highest write stamp across every consumed bid level.
            maxWriteSeq = Math.max(maxWriteSeq, book.bidWriteSeqAt(i));
        }
        if (sold < qtyToSell || quoteReceived < filter.minNotional()) {
            return; // insufficient displayed depth to fill the intended (quantized) size
        }
        out.worstPriceFixed = worstPrice;
        out.baseQtyFixed = qtyToSell;
        out.quoteFixed = quoteReceived;
        out.maxWriteSeq = maxWriteSeq;
        out.outputAmount = FixedPoint.mulDiv(quoteReceived, filter.takerFeeMultiplierFixed(), FixedPoint.SCALE);
        out.filled = true;
    }
}

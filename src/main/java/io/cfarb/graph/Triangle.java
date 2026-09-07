package io.cfarb.graph;

import io.cfarb.model.SymbolFilter;
import io.cfarb.model.Side;

/**
 * One configured 3-leg cycle, fully resolved at startup: symbol indices (for O(1)
 * {@link io.cfarb.book.BookRegistry} lookups), trade sides, and each leg's real exchange filter
 * (lot step / min qty / min notional / live taker fee — cf-arb-bot-plan.md §5.3) are all
 * precomputed once so the hot path never does a map lookup or string comparison.
 *
 * <p>Legs are stored in EXECUTION order (leg 0 spends the anchor stablecoin, leg 2 returns to it) —
 * unlike the Python research pipeline's {@code Cycle.trade_legs()}, which carries whatever
 * arbitrary rotation {@code networkx.simple_cycles} emitted and has to be rotated at evaluation
 * time (see {@code depth_notional_at_row}'s docstring). Config-driven construction means this bot
 * never needs that rotation step — {@code cf-bot.triangles.<name>.legs} is already written in
 * anchor-first order (see application.properties).
 *
 * <p>{@code fromAsset}/{@code toAsset} were added by cf-arb-bot-review-plan.md Tier 1 step 1.4 —
 * the independent review's Major finding that {@code exec.Unwinder} reversed the FAILED leg's
 * symbol using the held amount as if it were that leg's own base/quote asset, without knowing
 * which asset was actually in hand. These are also what {@link TriangleRegistry} uses to validate
 * that a configured triangle's legs actually chain together and close at the anchor asset (new
 * defect found during that review: nothing previously checked this).
 */
public record Triangle(
        String name,
        int[] symbolIndex,   // length 3, into BookRegistry
        Side[] side,         // length 3
        SymbolFilter[] filter, // length 3, resolved once at startup
        String[] fromAsset,  // length 3 -- the asset spent entering this leg
        String[] toAsset     // length 3 -- the asset received leaving this leg
) {
    public Triangle {
        if (symbolIndex.length != 3 || side.length != 3 || filter.length != 3
                || fromAsset.length != 3 || toAsset.length != 3) {
            throw new IllegalArgumentException("Triangle must have exactly 3 legs: " + name);
        }
    }

    /** Derive a leg's from/to asset from its side and filter: an ASK leg buys base with quote
     * (from=quote, to=base); a BID leg sells base for quote (from=base, to=quote). */
    public static String legFromAsset(Side side, SymbolFilter filter) {
        return side == Side.ASK ? filter.quoteAsset() : filter.baseAsset();
    }

    public static String legToAsset(Side side, SymbolFilter filter) {
        return side == Side.ASK ? filter.baseAsset() : filter.quoteAsset();
    }
}

package io.cfarb.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.book.BookRegistry;
import io.cfarb.feed.MexcDepthDecoder;
import io.cfarb.graph.Triangle;
import io.cfarb.model.Side;
import io.cfarb.model.SymbolFilter;
import io.cfarb.util.FixedPoint;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The failure matrix cf-arb-bot-review-plan.md calls out as "the test that would have caught the
 * $7.8M order": the FIRST independent review's Major finding that the previous {@link Unwinder}
 * reversed the FAILED leg's symbol using the amount being sent INTO that leg, rather than the
 * actual inventory acquired by whichever leg preceded it.
 *
 * <p>Extended for REVIEW.md's second independent review, MAJ-02: entry-boundary reversal pricing
 * fails deterministically in both directions (a SELL at the price you just bought at needs the
 * market to rise by the full spread; a BUY at the price you just sold at needs it to fall) --
 * {@link Unwinder} now crosses the CURRENT top of book instead, clamped to the symbol's
 * {@code PERCENT_PRICE_BY_SIDE} band, with a documented {@code MARKET}/stranded fallback when no
 * book is available to price against at all.
 *
 * <p>Triangle under test throughout: {@code usdt-btc-xrp-fwd} —
 * {@code BTCUSDT:ASK, XRPBTC:ASK, XRPUSDT:BID} (anchor USDT).
 */
class UnwinderTest {

    private static SymbolFilter filter(String symbol, String base, String quote, double qtyStep, int qtyDecimals,
                                        double minQty, double minNotional, int priceDecimals, double takerBps,
                                        Set<String> orderTypes, double bidMultiplierUp, double askMultiplierDown) {
        long feeMultiplierFixed = FixedPoint.fromDouble(1.0 - takerBps / 10_000.0);
        return new SymbolFilter(symbol, base, quote, FixedPoint.fromDouble(qtyStep), qtyDecimals,
                FixedPoint.fromDouble(minQty), FixedPoint.fromDouble(minNotional), priceDecimals, takerBps,
                feeMultiplierFixed, orderTypes, bidMultiplierUp, askMultiplierDown);
    }

    // Real live PERCENT_PRICE_BY_SIDE bands as of 2026-09-07 (config/mexc_filters.json).
    private static final SymbolFilter BTCUSDT = filter("BTCUSDT", "BTC", "USDT", 1e-6, 8, 1e-6, 1.0, 2, 5.0,
            Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.005, 0.005);
    // priceDecimals clamped to 8: MEXC's real XRPBTC quoteAssetPrecision is 9, but this system's
    // FixedPoint.SCALE=1e8 can only represent 8 -- see SymbolFilterLoader's clamp.
    private static final SymbolFilter XRPBTC = filter("XRPBTC", "XRP", "BTC", 0.01, 2, 10.0, 0.000005, 8, 5.0,
            Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.1, 0.1);
    private static final SymbolFilter XRPUSDT = filter("XRPUSDT", "XRP", "USDT", 0.01, 2, 0.1, 1.0, 4, 0.0,
            Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.02, 0.02);
    // Real live orderTypes as of 2026-09-07: this symbol advertises NO MARKET at all.
    private static final SymbolFilter ETHUSDC_NO_MARKET = filter("ETHUSDC", "ETH", "USDC", 1e-6, 8, 1e-6, 1.0, 2, 0.0,
            Set.of("LIMIT", "LIMIT_MAKER"), 0.02, 0.02);
    // usdt-btc-usdc-fwd's middle leg: a BID leg whose REVERSAL is therefore a BUY, and which does
    // advertise MARKET -- the combination that produced the 100-BTC market order.
    private static final SymbolFilter BTCUSDC = filter("BTCUSDC", "BTC", "USDC", 1e-6, 6, 1e-6, 1.0, 2, 5.0,
            Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.02, 0.02);
    private static final SymbolFilter USDCUSDT = filter("USDCUSDT", "USDC", "USDT", 0.01, 2, 1.0, 1.0, 5, 0.0,
            Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.02, 0.02);

    /** {@code usdt-btc-usdc-fwd} — {@code BTCUSDT:ASK, BTCUSDC:BID, USDCUSDT:BID} (anchor USDT).
     * Leg 1 is a BID, so reversing it BUYS, spending the USDC in hand. */
    private static Triangle btcUsdcTriangle() {
        Side[] sides = {Side.ASK, Side.BID, Side.BID};
        SymbolFilter[] filters = {BTCUSDT, BTCUSDC, USDCUSDT};
        String[] fromAsset = new String[3];
        String[] toAsset = new String[3];
        for (int i = 0; i < 3; i++) {
            fromAsset[i] = Triangle.legFromAsset(sides[i], filters[i]);
            toAsset[i] = Triangle.legToAsset(sides[i], filters[i]);
        }
        return new Triangle("usdt-btc-usdc-fwd", new int[]{0, 1, 2}, sides, filters, fromAsset, toAsset);
    }

    private static BookRegistry btcUsdcBooks() {
        return new BookRegistry(List.of("BTCUSDT", "BTCUSDC", "USDCUSDT"), 1, 3600);
    }

    private static Triangle triangle() {
        Side[] sides = {Side.ASK, Side.ASK, Side.BID};
        SymbolFilter[] filters = {BTCUSDT, XRPBTC, XRPUSDT};
        String[] fromAsset = new String[3];
        String[] toAsset = new String[3];
        for (int i = 0; i < 3; i++) {
            fromAsset[i] = Triangle.legFromAsset(sides[i], filters[i]);
            toAsset[i] = Triangle.legToAsset(sides[i], filters[i]);
        }
        return new Triangle("usdt-btc-xrp-fwd", new int[]{0, 1, 2}, sides, filters, fromAsset, toAsset);
    }

    /** BookRegistry whose symbol order matches {@link #triangle()}'s {@code symbolIndex}. Books are
     * left untrusted/empty (no top-of-book) unless a test seeds them via {@link #seedTop}. */
    private static BookRegistry books() {
        return new BookRegistry(List.of("BTCUSDT", "XRPBTC", "XRPUSDT"), 1, 3600);
    }

    private static void seedTop(BookRegistry books, int symbolIndex, double bidPrice, double askPrice) {
        seedTopAtTime(books, symbolIndex, bidPrice, askPrice, System.nanoTime());
    }

    /** Third-pass review finding (L1): seed a book as of an explicit receive-time nanoTime, so a
     * test can simulate a top-of-book that is still PUBLISHED (non-sentinel, trusted) but has gone
     * stale -- {@code nowNanos} in the past relative to when {@link Unwinder} itself later calls
     * {@code System.nanoTime()} to check the age gate. */
    private static void seedTopAtTime(BookRegistry books, int symbolIndex, double bidPrice, double askPrice,
                                       long nowNanos) {
        MexcDepthDecoder.DepthFrame f = new MexcDepthDecoder.DepthFrame();
        f.fromVersion = -1;
        f.toVersion = -1;
        f.sendTimeMs = 0;
        f.bidPx[0] = FixedPoint.fromDouble(bidPrice);
        f.bidQty[0] = FixedPoint.fromDouble(1.0);
        f.bidCount = 1;
        f.askPx[0] = FixedPoint.fromDouble(askPrice);
        f.askQty[0] = FixedPoint.fromDouble(1.0);
        f.askCount = 1;
        books.book(symbolIndex).apply(f, nowNanos);
    }

    private static final long DEFAULT_CROSS_BPS = 40;

    private static Unwinder unwinder(FakeMexcOrderApi api, BookRegistry books) {
        return unwinder(api, books, DEFAULT_CROSS_BPS);
    }

    private static Unwinder unwinder(FakeMexcOrderApi api, BookRegistry books, long crossBps) {
        return new Unwinder(api, "IOC", 1500, books, crossBps);
    }

    /** Extracts one query-string parameter's value, e.g. {@code param(params, "price")}. */
    private static String param(String queryString, String name) {
        for (String kv : queryString.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(name)) {
                return kv.substring(eq + 1);
            }
        }
        return null;
    }

    @Test
    void leg0ZeroFillPlacesNoOrderAtAll() {
        // This is the exact scenario that previously submitted "SELL 100 BTC @ 77850" -- leg 0
        // never acquired anything, so there is nothing to unwind.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.ZERO_FILL;
        state.legs[0].executedBaseQtyFixed = 0;

        Unwinder.Result r = unwinder(api, books()).unwind(triangle(), state, 0);

        assertFalse(r.anyOrderPlaced(), "leg 0 never filled -- no reversal order should ever be placed");
        assertEquals(0, r.recoveredAnchorFixed());
        assertEquals(0, api.placeOrderCalls, "no order of any kind, on any symbol, should be submitted");
        assertFalse(r.unrecoverable());
    }

    @Test
    void leg1FailureReversesLeg0sSymbolNeverLeg1s() {
        // leg 0 (BTCUSDT) filled; leg 1 (XRPBTC) failed. The bug: the old Unwinder would reverse
        // leg 1's symbol (XRPBTC) using leg 1's INPUT amount (BTC), not leg 0's actual BTC holding.
        // Correct behavior: reverse leg 0's OWN symbol (BTCUSDT), never touching XRPBTC.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = books();
        seedTop(books, 0, 77840.0, 77850.0); // BTCUSDT
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].requestedBaseQtyFixed = FixedPoint.fromDouble(0.001285);
        state.legs[0].requestedPriceFixed = FixedPoint.fromDouble(77850.0);
        state.legs[0].executedBaseQtyFixed = FixedPoint.fromDouble(0.001285);
        state.legs[0].executedQuoteFixed = FixedPoint.fromDouble(0.001285 * 77850.0);
        state.legs[1].status = CycleState.LegStatus.ZERO_FILL;
        state.legs[1].executedBaseQtyFixed = 0;

        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001284), FixedPoint.fromDouble(99.95),
                "FILLED", "rev-0");

        Unwinder.Result r = unwinder(api, books).unwind(triangle(), state, 1);

        assertTrue(r.anyOrderPlaced());
        assertEquals(1, api.placeOrderCalls, "exactly one reversal order -- straight back to the anchor from leg 0");
        assertTrue(api.placedParams.get(0).contains("symbol=BTCUSDT"),
                "must reverse leg 0's OWN symbol: " + api.placedParams.get(0));
        assertFalse(api.placedParams.get(0).contains("symbol=XRPBTC"),
                "must NEVER trade the failed leg's symbol when leg 0 is what's actually held");
        assertTrue(api.placedParams.get(0).contains("side=SELL"), "reversing a BUY (ASK) means selling the base back");
        assertTrue(r.recoveredAnchorFixed() > FixedPoint.fromDouble(90.0)
                        && r.recoveredAnchorFixed() < FixedPoint.fromDouble(100.0),
                "recovered anchor should be close to the ~$100 originally spent, minus two rounds of fees");
        assertFalse(r.unrecoverable());
    }

    @Test
    void leg2FullFillIsAlreadyAnchorDenominatedNoOrderPlaced() {
        // leg 2 (XRPUSDT BID) always returns to the anchor by construction -- when it consumed
        // everything it was handed, its own proceeds ARE the recovered anchor amount and there is
        // nothing left in any other asset to reverse.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[1].status = CycleState.LegStatus.FILLED;
        state.legs[2].status = CycleState.LegStatus.PARTIAL;
        state.legs[2].inputAmountFixed = FixedPoint.fromDouble(60.0); // handed 60 XRP...
        state.legs[2].requestedBaseQtyFixed = FixedPoint.fromDouble(60.0);
        state.legs[2].executedBaseQtyFixed = FixedPoint.fromDouble(60.0); // ...sold all 60
        state.legs[2].executedQuoteFixed = FixedPoint.fromDouble(50.0); // 0 bps taker on XRPUSDT

        Unwinder.Result r = unwinder(api, books()).unwind(triangle(), state, 2);

        assertFalse(r.anyOrderPlaced(), "leg 2's proceeds are already anchor-denominated -- no reversal needed");
        assertEquals(0, api.placeOrderCalls);
        assertEquals(FixedPoint.fromDouble(50.0), r.recoveredAnchorFixed());
    }

    @Test
    void leg2PartialFillReversesTheUnsoldRemainderInsteadOfAbandoningIt() {
        // THIRD-PASS REVIEW FINDING. This test previously asserted the DEFECT: leg 2 requested 100
        // XRP, filled 60, and the unwinder returned "already anchor-denominated" with the 40 unsold
        // XRP neither reversed, nor logged as stranded, nor flagged unrecoverable -- CycleExecutor
        // then booked that XRP as a 100% loss while it sat in the account. Unwinder's stated model
        // ("at most ONE leg's output is ever in hand and not yet converted forward") is false for
        // exactly the PARTIAL case the executor routes into it.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = books();
        seedTop(books, 1, 0.0000169, 0.0000171); // XRPBTC
        seedTop(books, 0, 77840.0, 77850.0);     // BTCUSDT
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[1].status = CycleState.LegStatus.FILLED;
        state.legs[2].status = CycleState.LegStatus.PARTIAL;
        state.legs[2].inputAmountFixed = FixedPoint.fromDouble(220.0);      // handed 220 XRP
        state.legs[2].requestedBaseQtyFixed = FixedPoint.fromDouble(220.0);
        state.legs[2].executedBaseQtyFixed = FixedPoint.fromDouble(90.0);   // only 90 sold
        state.legs[2].executedQuoteFixed = FixedPoint.fromDouble(124.0);    // 0 bps taker on XRPUSDT
        // ...leaving 130 XRP that must be walked back XRPBTC -> BTCUSDT -> USDT.

        api.scriptQuery("XRPBTC", FixedPoint.fromDouble(130.0), FixedPoint.fromDouble(0.002197),
                "FILLED", "rev-1");
        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.002195), FixedPoint.fromDouble(170.86),
                "FILLED", "rev-0");

        Unwinder.Result r = unwinder(api, books).unwind(triangle(), state, 2);

        assertEquals(2, api.placeOrderCalls, "the 130 unsold XRP must be walked back through legs 1 and 0");
        assertTrue(api.placedParams.get(0).contains("symbol=XRPBTC"));
        assertTrue(api.placedParams.get(0).contains("side=SELL"));
        assertEquals("130.00", param(api.placedParams.get(0), "quantity"),
                "must reverse the UNSOLD remainder (220 handed - 90 sold), not leg 2's proceeds: "
                        + api.placedParams.get(0));
        assertTrue(api.placedParams.get(1).contains("symbol=BTCUSDT"));
        assertTrue(r.recoveredAnchorFixed() > FixedPoint.fromDouble(280.0),
                "recovered anchor must be leg 2's own 124 USDT PLUS the ~170 USDT the remainder walked "
                        + "back to, not 124 alone -- got "
                        + FixedPoint.toDouble(r.recoveredAnchorFixed()));
    }

    @Test
    void partialLegRemainderIsCarriedIntoTheNextReversal() {
        // Same defect one leg earlier: leg 1 (XRPBTC ASK) was handed 0.001284 BTC and spent only
        // 0.00077 of it before being canceled. Reversing leg 1 returns ~0.00077 BTC -- the other
        // ~0.000514 BTC is still sitting there and must be sold back through leg 0 too, not left
        // behind and booked as a loss.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = books();
        seedTop(books, 1, 0.0000169, 0.0000171);
        seedTop(books, 0, 77840.0, 77850.0);
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].executedBaseQtyFixed = FixedPoint.fromDouble(0.001285);
        state.legs[0].executedQuoteFixed = FixedPoint.fromDouble(100.0);
        state.legs[1].status = CycleState.LegStatus.PARTIAL;
        state.legs[1].inputAmountFixed = FixedPoint.fromDouble(0.001284); // BTC handed to leg 1
        state.legs[1].requestedBaseQtyFixed = FixedPoint.fromDouble(75.0);
        state.legs[1].executedBaseQtyFixed = FixedPoint.fromDouble(45.0);  // XRP acquired
        state.legs[1].executedQuoteFixed = FixedPoint.fromDouble(0.00077); // BTC actually spent

        // Reversing leg 1: sell the 45 XRP back for ~0.00076 BTC.
        api.scriptQuery("XRPBTC", FixedPoint.fromDouble(45.0), FixedPoint.fromDouble(0.00076),
                "FILLED", "rev-1");
        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001273), FixedPoint.fromDouble(99.0),
                "FILLED", "rev-0");

        unwinder(api, books).unwind(triangle(), state, 2);

        assertEquals(2, api.placeOrderCalls);
        // 0.00076 BTC recovered from the XRP reversal (minus 5bps) + 0.000514 BTC never spent.
        double leg0SellQty = Double.parseDouble(param(api.placedParams.get(1), "quantity"));
        assertTrue(leg0SellQty > 0.00125,
                "leg 0's reversal must sell the reversal proceeds PLUS the ~0.000514 BTC leg 1 never "
                        + "consumed (~0.001274 BTC total), not the ~0.00076 proceeds alone -- got " + leg0SellQty);
    }

    @Test
    void reversalThatDoesNotFillLeavesInventoryStrandedNotFabricatedRecovery() {
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = books();
        seedTop(books, 0, 77840.0, 77850.0);
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].requestedPriceFixed = FixedPoint.fromDouble(77850.0);
        state.legs[0].executedBaseQtyFixed = FixedPoint.fromDouble(0.001285);
        state.legs[0].executedQuoteFixed = FixedPoint.fromDouble(0.001285 * 77850.0);
        state.legs[1].status = CycleState.LegStatus.ZERO_FILL;

        // the reversal order itself gets canceled with zero fill
        api.scriptQuery("BTCUSDT", 0, 0, "CANCELED", "rev-0");

        Unwinder.Result r = unwinder(api, books).unwind(triangle(), state, 1);

        assertTrue(r.anyOrderPlaced(), "a reversal attempt WAS made");
        assertEquals(0, r.recoveredAnchorFixed(), "an unfilled reversal must never be reported as recovered anchor");
        assertFalse(r.unrecoverable(), "inventory is unconverted but still present -- not the no-pricing-source case");
    }

    @Test
    void leg1HeldRequiresTwoHopReversalThroughLeg0BackToAnchor() {
        // leg 1 (XRPBTC) filled but leg 2 (XRPUSDT) failed -- reversing leg 1 alone only returns to
        // BTC (leg 0's output), not the anchor. Unwinder must then ALSO reverse leg 0.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = books();
        seedTop(books, 1, 0.0000169, 0.0000171); // XRPBTC
        seedTop(books, 0, 77840.0, 77850.0); // BTCUSDT
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].requestedPriceFixed = FixedPoint.fromDouble(77850.0);
        state.legs[1].status = CycleState.LegStatus.FILLED;
        state.legs[1].executedBaseQtyFixed = FixedPoint.fromDouble(500.0); // XRP acquired
        state.legs[1].requestedPriceFixed = FixedPoint.fromDouble(0.000017); // BTC per XRP
        state.legs[2].status = CycleState.LegStatus.ZERO_FILL;

        api.scriptQuery("XRPBTC", FixedPoint.fromDouble(499.75), FixedPoint.fromDouble(0.00849575),
                "FILLED", "rev-1");
        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.008491), FixedPoint.fromDouble(660.98),
                "FILLED", "rev-0");

        Unwinder.Result r = unwinder(api, books).unwind(triangle(), state, 2);

        assertEquals(2, api.placeOrderCalls, "two hops: XRPBTC back to BTC, then BTCUSDT back to the anchor");
        assertTrue(api.placedParams.get(0).contains("symbol=XRPBTC"), "first hop reverses leg 1");
        assertTrue(api.placedParams.get(1).contains("symbol=BTCUSDT"), "second hop reverses leg 0, back to anchor");
        assertTrue(r.anyOrderPlaced());
        assertTrue(r.recoveredAnchorFixed() > FixedPoint.fromDouble(600.0),
                "should recover most of the ~$660 the two-hop reversal produced, minus fees");
    }

    @Test
    void reversalPricingCrossesTheCurrentBookNotTheStaleEntryBoundary() {
        // REVIEW.md MAJ-02: leg 0 originally BOUGHT BTC at 77850 (ASK). The market has since moved
        // -- reversing (SELLING) must price off the CURRENT bid, not the stale 77850 entry price,
        // and must cross BELOW that current bid to be marketable, not sit at a boundary that only
        // fills if the market happens to reverse on its own.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = books();
        double currentBestBid = 70_000.0; // the market fell hard since leg 0's 77850 entry
        seedTop(books, 0, currentBestBid, 70_010.0);
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].requestedPriceFixed = FixedPoint.fromDouble(77850.0); // stale entry boundary
        state.legs[0].executedBaseQtyFixed = FixedPoint.fromDouble(0.001285);
        state.legs[0].executedQuoteFixed = FixedPoint.fromDouble(0.001285 * 77850.0);
        state.legs[1].status = CycleState.LegStatus.ZERO_FILL;

        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001284), FixedPoint.fromDouble(89.0), "FILLED", "rev-0");

        unwinder(api, books).unwind(triangle(), state, 1);

        String submittedPrice = param(api.placedParams.get(0), "price");
        double price = Double.parseDouble(submittedPrice);
        assertTrue(price < currentBestBid,
                "a SELL reversal must cross BELOW the current bid to be marketable -- got " + price
                        + " vs current bid " + currentBestBid);
        assertTrue(price > 77850.0 * 0.5,
                "sanity: must still be priced off the CURRENT book (~70000), not some unrelated value");
        assertTrue(Math.abs(price - 77850.0) > 1000.0,
                "must NOT reuse the stale 77850 entry-boundary price REVIEW.md MAJ-02 flagged");
    }

    @Test
    void reversalPriceClampsInsideThePercentPriceBySideband() {
        // An oversized cross buffer must never itself be why a reversal gets rejected -- clamp
        // inside BTCUSDT's real 0.5% PERCENT_PRICE_BY_SIDE band rather than submitting a price the
        // venue is guaranteed to bounce.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = books();
        double currentBestBid = 77_000.0;
        seedTop(books, 0, currentBestBid, 77_010.0);
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].requestedPriceFixed = FixedPoint.fromDouble(77850.0);
        state.legs[0].executedBaseQtyFixed = FixedPoint.fromDouble(0.001285);
        state.legs[0].executedQuoteFixed = FixedPoint.fromDouble(0.001285 * 77850.0);
        state.legs[1].status = CycleState.LegStatus.ZERO_FILL;

        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001284), FixedPoint.fromDouble(89.0), "FILLED", "rev-0");

        // 5000 bps (50%) would price WAY below the venue's 0.5% askMultiplierDown floor.
        unwinder(api, books, 5000).unwind(triangle(), state, 1);

        double price = Double.parseDouble(param(api.placedParams.get(0), "price"));
        double floor = currentBestBid * (1.0 - BTCUSDT.askMultiplierDown());
        double naiveUnclamped = currentBestBid * (1.0 - 0.50);
        assertTrue(price > naiveUnclamped, "the raw 50% cross must have been clamped up toward the floor");
        assertTrue(price >= floor * 0.99, "clamped price should sit at (or very near, given rounding) the venue's "
                + "own price-band floor=" + floor + ", got " + price);
    }

    @Test
    void clampToPriceBandFloorSurvivesOnWireTruncationWithoutFallingBelowTheVenueFloor() {
        // Third-pass review finding (L2): clampToPriceBand computes the floor at full 1e8
        // precision, but FixedPoint.toPlainString later TRUNCATES DOWN to priceDecimals when the
        // price is rendered onto the wire -- if the clamped price sits exactly at a floor that isn't
        // already tick-aligned, truncation could push the SUBMITTED price below the venue's own
        // floor. Pick a referencePrice/askMultiplierDown pair whose raw floor is deliberately NOT
        // aligned to BTCUSDT's 2 price decimals.
        long referencePrice = FixedPoint.fromDouble(77777.77);
        // askMultiplierDown = 0.005 -> raw floor = 77777.77 * 0.995 = 77378.81615, NOT 2-decimal-aligned.
        long crossedBelowFloor = FixedPoint.fromDouble(70000.0); // deliberately far below the floor
        long clamped = Unwinder.clampToPriceBand(crossedBelowFloor, Side.BID, BTCUSDT, referencePrice);

        String onWire = FixedPoint.toPlainString(clamped, BTCUSDT.priceDecimals());
        double onWireValue = Double.parseDouble(onWire);
        double trueFloor = 77777.77 * (1.0 - BTCUSDT.askMultiplierDown());
        assertTrue(onWireValue >= trueFloor, "the price actually rendered onto the wire (" + onWire
                + ") must never fall below the venue's real floor (" + trueFloor + ") after truncation");
    }

    @Test
    void roundUpToDecimalsIsExactWhenAlreadyAlignedAndBumpsUpOtherwise() {
        assertEquals(FixedPoint.fromDouble(1.23), Unwinder.roundUpToDecimals(FixedPoint.fromDouble(1.23), 2),
                "an already-aligned value must round-trip exactly, never bump up spuriously");
        long slightlyAbove = FixedPoint.fromDouble(1.2300001);
        assertEquals(FixedPoint.fromDouble(1.24), Unwinder.roundUpToDecimals(slightlyAbove, 2));
    }

    @Test
    void staleTopOfBookFallsBackToMarketRatherThanCrossingAManyMinutesOldPrice() {
        // Third-pass review finding (L1): L2Book only clears topBid/topAskFixed on an explicit
        // reset() (disconnect or version-chain gap) -- a connection that goes quietly stale WITHOUT
        // either (a half-open socket the watchdog hasn't caught yet) still reports a real,
        // non-sentinel top, just a many-minutes-old one. Crossing that by the fixed unwindCrossBps
        // buffer is the MAJ-02 failure one level removed. Seed the book 10 SECONDS in the past
        // (Unwinder's own staleness gate is 5s) relative to when Unwinder itself checks the age.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = books();
        long staleSeedNanos = System.nanoTime() - 10_000_000_000L;
        seedTopAtTime(books, 0, 77840.0, 77850.0, staleSeedNanos); // BTCUSDT, 10s stale
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].requestedPriceFixed = FixedPoint.fromDouble(77850.0);
        state.legs[0].executedBaseQtyFixed = FixedPoint.fromDouble(0.001285);
        state.legs[0].executedQuoteFixed = FixedPoint.fromDouble(0.001285 * 77850.0);
        state.legs[1].status = CycleState.LegStatus.ZERO_FILL;

        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001284), FixedPoint.fromDouble(99.0), "FILLED", "rev-0");

        Unwinder.Result r = unwinder(api, books).unwind(triangle(), state, 1);

        assertEquals(1, api.placeOrderCalls);
        assertTrue(api.placedParams.get(0).contains("type=MARKET"), "a 10s-stale top must be treated the same "
                + "as no top at all -- must fall back to MARKET: " + api.placedParams.get(0));
        assertFalse(api.placedParams.get(0).contains("price="), "a MARKET order must never carry a price");
        assertFalse(r.unrecoverable());
    }

    @Test
    void freshTopOfBookStillPricesNormallyWithinTheStalenessWindow() {
        // No regression: a top that is recent (well inside the 5s staleness gate) must still be
        // used to price the reversal, not fall back to MARKET unnecessarily.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = books();
        seedTop(books, 0, 77840.0, 77850.0); // seeded "now" -- age ~microseconds
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].requestedPriceFixed = FixedPoint.fromDouble(77850.0);
        state.legs[0].executedBaseQtyFixed = FixedPoint.fromDouble(0.001285);
        state.legs[0].executedQuoteFixed = FixedPoint.fromDouble(0.001285 * 77850.0);
        state.legs[1].status = CycleState.LegStatus.ZERO_FILL;

        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001284), FixedPoint.fromDouble(99.95), "FILLED", "rev-0");

        unwinder(api, books).unwind(triangle(), state, 1);

        assertTrue(api.placedParams.get(0).contains("price="), "a fresh top must still be priced, not sent to "
                + "MARKET: " + api.placedParams.get(0));
        assertFalse(api.placedParams.get(0).contains("type=MARKET"));
    }

    @Test
    void noLiveBookFallsBackToMarketWhenTheSymbolSupportsIt() {
        // BTCUSDT's book is never seeded here -- untrusted/empty, simulating a reconnect mid-unwind.
        // BTCUSDT advertises MARKET, so the fallback must use it rather than stranding the position.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = books(); // no seedTop call at all
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].requestedPriceFixed = FixedPoint.fromDouble(77850.0);
        state.legs[0].executedBaseQtyFixed = FixedPoint.fromDouble(0.001285);
        state.legs[0].executedQuoteFixed = FixedPoint.fromDouble(0.001285 * 77850.0);
        state.legs[1].status = CycleState.LegStatus.ZERO_FILL;

        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001284), FixedPoint.fromDouble(99.0), "FILLED", "rev-0");

        Unwinder.Result r = unwinder(api, books).unwind(triangle(), state, 1);

        assertEquals(1, api.placeOrderCalls);
        assertTrue(api.placedParams.get(0).contains("type=MARKET"), "no book to price against -- must fall back to "
                + "MARKET: " + api.placedParams.get(0));
        assertFalse(api.placedParams.get(0).contains("price="), "a MARKET order must never carry a price parameter");
        assertFalse(r.unrecoverable());
        assertTrue(r.recoveredAnchorFixed() > 0);
    }

    @Test
    void marketFallbackOnABuyReversalIsSizedInQuoteNotAsABaseQuantity() {
        // THIRD-PASS REVIEW FINDING -- the regression the existing MARKET test could not catch,
        // because it only exercised the SELL direction (where the held amount is already
        // base-denominated and the placeholder 1.0 reference price was a harmless no-op).
        //
        // usdt-btc-usdc-fwd, leg 2 fails: ~100 USDC in hand, reversing leg 1 (BTCUSDC:BID) means
        // BUYING BTC. Feeding a fabricated price of 1.0 into quantizeLeg turned "100 USDC" into
        // "quantity=100" -- a 100 BTC (~$7.7M) market order. A MARKET BUY is sized by quoteOrderQty.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = btcUsdcBooks(); // no seedTop: books reset by a reconnect mid-unwind
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].executedBaseQtyFixed = FixedPoint.fromDouble(0.001285);
        state.legs[0].executedQuoteFixed = FixedPoint.fromDouble(100.0);
        state.legs[1].status = CycleState.LegStatus.FILLED;
        state.legs[1].executedBaseQtyFixed = FixedPoint.fromDouble(0.001285); // BTC sold
        state.legs[1].executedQuoteFixed = FixedPoint.fromDouble(100.0);      // USDC received
        state.legs[2].status = CycleState.LegStatus.ZERO_FILL;

        api.scriptQuery("BTCUSDC", FixedPoint.fromDouble(0.001283), FixedPoint.fromDouble(99.94),
                "FILLED", "rev-1");
        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001282), FixedPoint.fromDouble(99.80),
                "FILLED", "rev-0");

        Unwinder.Result r = unwinder(api, books).unwind(btcUsdcTriangle(), state, 2);

        String buy = api.placedParams.get(0);
        assertTrue(buy.contains("symbol=BTCUSDC") && buy.contains("side=BUY") && buy.contains("type=MARKET"), buy);
        assertNull(param(buy, "quantity"),
                "a MARKET BUY must NOT carry a base quantity -- that is the 100-BTC bug: " + buy);
        assertEquals("99.95", param(buy, "quoteOrderQty"),
                "must spend the ~99.95 USDC actually held, sized in the QUOTE asset: " + buy);
        // The SELL direction must be untouched: still a base quantity, still no quote sizing.
        String sell = api.placedParams.get(1);
        assertTrue(sell.contains("symbol=BTCUSDT") && sell.contains("side=SELL"), sell);
        assertNull(param(sell, "quoteOrderQty"), "a MARKET SELL is sized by base quantity: " + sell);
        assertEquals(0.001282, Double.parseDouble(param(sell, "quantity")), 1e-9, sell);
        assertTrue(r.recoveredAnchorFixed() > FixedPoint.fromDouble(90.0),
                "both hops filled -- the position is recovered, not stranded");
    }

    @Test
    void noLiveBookAndNoMarketSupportIsUnrecoverableWithoutSubmittingADoomedOrder() {
        // ETHUSDC advertises no MARKET at all (confirmed live 2026-09-07) -- with no book to price a
        // LIMIT reversal against either, there is no safe order to submit. Must strand and flag
        // unrecoverable rather than guess a limit price certain to miss.
        Side[] sides = {Side.ASK, Side.ASK, Side.BID};
        SymbolFilter[] filters = {ETHUSDC_NO_MARKET, XRPBTC, XRPUSDT};
        String[] fromAsset = new String[3];
        String[] toAsset = new String[3];
        for (int i = 0; i < 3; i++) {
            fromAsset[i] = Triangle.legFromAsset(sides[i], filters[i]);
            toAsset[i] = Triangle.legToAsset(sides[i], filters[i]);
        }
        Triangle t = new Triangle("usdt-eth-xrp-fwd", new int[]{0, 1, 2}, sides, filters, fromAsset, toAsset);

        FakeMexcOrderApi api = new FakeMexcOrderApi();
        BookRegistry books = books(); // ETHUSDC's book (index 0, reusing the BTCUSDT slot) never seeded
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].executedBaseQtyFixed = FixedPoint.fromDouble(0.03);
        state.legs[0].executedQuoteFixed = FixedPoint.fromDouble(100.0);
        state.legs[1].status = CycleState.LegStatus.ZERO_FILL;

        Unwinder.Result r = unwinder(api, books).unwind(t, state, 1);

        assertFalse(r.anyOrderPlaced(), "no safe order exists to submit -- must not guess a doomed limit price");
        assertEquals(0, r.recoveredAnchorFixed());
        assertTrue(r.unrecoverable(), "no pricing source AND no MARKET fallback must be flagged unrecoverable");
        assertEquals(0, api.placeOrderCalls);
    }
}

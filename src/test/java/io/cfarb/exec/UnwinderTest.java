package io.cfarb.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.graph.Triangle;
import io.cfarb.model.Side;
import io.cfarb.model.SymbolFilter;
import io.cfarb.util.FixedPoint;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The failure matrix cf-arb-bot-review-plan.md calls out as "the test that would have caught the
 * $7.8M order": the independent review's Major finding that the previous {@link Unwinder} reversed
 * the FAILED leg's symbol using the amount being sent INTO that leg, rather than the actual
 * inventory acquired by whichever leg preceded it.
 *
 * <p>Triangle under test throughout: {@code usdt-btc-xrp-fwd} —
 * {@code BTCUSDT:ASK, XRPBTC:ASK, XRPUSDT:BID} (anchor USDT).
 */
class UnwinderTest {

    private static SymbolFilter filter(String symbol, String base, String quote, double qtyStep, int qtyDecimals,
                                        double minQty, double minNotional, int priceDecimals, double takerBps) {
        long feeMultiplierFixed = FixedPoint.fromDouble(1.0 - takerBps / 10_000.0);
        return new SymbolFilter(symbol, base, quote, FixedPoint.fromDouble(qtyStep), qtyDecimals,
                FixedPoint.fromDouble(minQty), FixedPoint.fromDouble(minNotional), priceDecimals, takerBps,
                feeMultiplierFixed, Set.of("LIMIT", "MARKET", "LIMIT_MAKER"));
    }

    private static final SymbolFilter BTCUSDT = filter("BTCUSDT", "BTC", "USDT", 1e-6, 8, 1e-6, 1.0, 2, 5.0);
    // priceDecimals clamped to 8: MEXC's real XRPBTC quoteAssetPrecision is 9, but this system's
    // FixedPoint.SCALE=1e8 can only represent 8 -- see SymbolFilterLoader's clamp.
    private static final SymbolFilter XRPBTC = filter("XRPBTC", "XRP", "BTC", 0.01, 2, 10.0, 0.000005, 8, 5.0);
    private static final SymbolFilter XRPUSDT = filter("XRPUSDT", "XRP", "USDT", 0.01, 2, 0.1, 1.0, 4, 0.0);

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

    private static Unwinder unwinder(FakeMexcOrderApi api) {
        return new Unwinder(api, "IMMEDIATE_OR_CANCEL", 1500);
    }

    @Test
    void leg0ZeroFillPlacesNoOrderAtAll() {
        // This is the exact scenario that previously submitted "SELL 100 BTC @ 77850" -- leg 0
        // never acquired anything, so there is nothing to unwind.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.ZERO_FILL;
        state.legs[0].executedBaseQtyFixed = 0;

        Unwinder.Result r = unwinder(api).unwind(triangle(), state, 0);

        assertFalse(r.anyOrderPlaced(), "leg 0 never filled -- no reversal order should ever be placed");
        assertEquals(0, r.recoveredAnchorFixed());
        assertEquals(0, api.placeOrderCalls, "no order of any kind, on any symbol, should be submitted");
    }

    @Test
    void leg1FailureReversesLeg0sSymbolNeverLeg1s() {
        // leg 0 (BTCUSDT) filled; leg 1 (XRPBTC) failed. The bug: the old Unwinder would reverse
        // leg 1's symbol (XRPBTC) using leg 1's INPUT amount (BTC), not leg 0's actual BTC holding.
        // Correct behavior: reverse leg 0's OWN symbol (BTCUSDT), never touching XRPBTC.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
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

        Unwinder.Result r = unwinder(api).unwind(triangle(), state, 1);

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
    }

    @Test
    void leg2PartialFillIsAlreadyAnchorDenominatedNoOrderPlaced() {
        // leg 2 (XRPUSDT BID) always returns to the anchor by construction -- if IT is the one that
        // partially filled, its own proceeds ARE the recovered anchor amount; nothing to reverse.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[1].status = CycleState.LegStatus.FILLED;
        state.legs[2].status = CycleState.LegStatus.PARTIAL;
        state.legs[2].requestedBaseQtyFixed = FixedPoint.fromDouble(100.0);
        state.legs[2].executedBaseQtyFixed = FixedPoint.fromDouble(60.0);
        state.legs[2].executedQuoteFixed = FixedPoint.fromDouble(50.0); // 0 bps taker on XRPUSDT

        Unwinder.Result r = unwinder(api).unwind(triangle(), state, 2);

        assertFalse(r.anyOrderPlaced(), "leg 2's proceeds are already anchor-denominated -- no reversal needed");
        assertEquals(0, api.placeOrderCalls);
        assertEquals(FixedPoint.fromDouble(50.0), r.recoveredAnchorFixed());
    }

    @Test
    void reversalThatDoesNotFillLeavesInventoryStrandedNotFabricatedRecovery() {
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        CycleState state = new CycleState("c");
        state.legs[0].status = CycleState.LegStatus.FILLED;
        state.legs[0].requestedPriceFixed = FixedPoint.fromDouble(77850.0);
        state.legs[0].executedBaseQtyFixed = FixedPoint.fromDouble(0.001285);
        state.legs[0].executedQuoteFixed = FixedPoint.fromDouble(0.001285 * 77850.0);
        state.legs[1].status = CycleState.LegStatus.ZERO_FILL;

        // the reversal order itself gets canceled with zero fill
        api.scriptQuery("BTCUSDT", 0, 0, "CANCELED", "rev-0");

        Unwinder.Result r = unwinder(api).unwind(triangle(), state, 1);

        assertTrue(r.anyOrderPlaced(), "a reversal attempt WAS made");
        assertEquals(0, r.recoveredAnchorFixed(), "an unfilled reversal must never be reported as recovered anchor");
    }

    @Test
    void leg1HeldRequiresTwoHopReversalThroughLeg0BackToAnchor() {
        // leg 1 (XRPBTC) filled but leg 2 (XRPUSDT) failed -- reversing leg 1 alone only returns to
        // BTC (leg 0's output), not the anchor. Unwinder must then ALSO reverse leg 0.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
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

        Unwinder.Result r = unwinder(api).unwind(triangle(), state, 2);

        assertEquals(2, api.placeOrderCalls, "two hops: XRPBTC back to BTC, then BTCUSDT back to the anchor");
        assertTrue(api.placedParams.get(0).contains("symbol=XRPBTC"), "first hop reverses leg 1");
        assertTrue(api.placedParams.get(1).contains("symbol=BTCUSDT"), "second hop reverses leg 0, back to anchor");
        assertTrue(r.anyOrderPlaced());
        assertTrue(r.recoveredAnchorFixed() > FixedPoint.fromDouble(600.0),
                "should recover most of the ~$660 the two-hop reversal produced, minus fees");
    }
}

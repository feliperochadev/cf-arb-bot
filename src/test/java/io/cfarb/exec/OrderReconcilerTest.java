package io.cfarb.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.model.Side;
import io.cfarb.model.SymbolFilter;
import io.cfarb.util.FixedPoint;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Direct unit coverage of {@link OrderReconciler} -- previously exercised only indirectly through
 * {@code CycleExecutorTest}'s async executor thread. Third-pass review findings M4/M5/M6 all live
 * entirely inside {@code submitPrebuiltAndReconcile}/{@code reconcileOnce}/{@code isNonTerminal},
 * so a synchronous, same-thread test against the real class (package-private, same package) is both
 * faster and more direct than routing everything through {@link CycleExecutor}'s background thread.
 */
class OrderReconcilerTest {

    private static final SymbolFilter BTCUSDT = new SymbolFilter("BTCUSDT", "BTC", "USDT",
            FixedPoint.fromDouble(1e-6), 6, FixedPoint.fromDouble(1e-6), FixedPoint.fromDouble(1.0), 2, 5.0,
            FixedPoint.fromDouble(0.9995), Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.005, 0.005);

    private static CycleState.Leg leg(String clientOrderId, double requestedQty) {
        CycleState.Leg leg = new CycleState.Leg(clientOrderId);
        leg.requestedBaseQtyFixed = FixedPoint.fromDouble(requestedQty);
        leg.requestedPriceFixed = FixedPoint.fromDouble(77850.0);
        return leg;
    }

    // ---- M4: not every 4xx placement rejection is definitive ------------------------------------

    @Test
    void definitiveRejectionCodeClassifiesRejectedPresubmitWithoutQuerying() {
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        api.makePlaceOrderFail(400); // default body carries code=-2010 (insufficient balance)
        OrderReconciler reconciler = new OrderReconciler(api, "IOC", 1500);
        CycleState.Leg leg = leg("c-0", 0.001);

        reconciler.submitAndReconcile("BTCUSDT", Side.ASK, BTCUSDT, leg);

        assertEquals(CycleState.LegStatus.REJECTED_PRESUBMIT, leg.status);
        assertEquals(0, api.queryOrderCalls, "a definitive rejection must never be followed by a doomed query");
    }

    @Test
    void ambiguousVenueCodeFallsThroughToReconciliationRatherThanAssumingRejection() {
        // Third-pass review finding (M4): -1007 means "send status unknown; execution status
        // unknown" -- the venue is TELLING us it doesn't know, so a 4xx here must still reconcile.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        api.makePlaceOrderFailAmbiguous(400);
        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001), FixedPoint.fromDouble(77.85), "FILLED", "o0");
        OrderReconciler reconciler = new OrderReconciler(api, "IOC", 1500);
        CycleState.Leg leg = leg("c-0", 0.001);

        reconciler.submitAndReconcile("BTCUSDT", Side.ASK, BTCUSDT, leg);

        assertEquals(1, api.queryOrderCalls, "an ambiguous 4xx must still reconcile, not be assumed rejected");
        assertEquals(CycleState.LegStatus.FILLED, leg.status,
                "the order actually reached the venue and filled -- REJECTED_PRESUBMIT would have been wrong");
    }

    @Test
    void unparseableRejectionBodyFailsClosedIntoReconciliationRatherThanAssumingRejection() {
        // A malformed/non-JSON 4xx body (e.g. an edge proxy/WAF response MEXC itself never
        // produced) must not be trusted as "definitely rejected" either.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        api.makePlaceOrderFailUnparseableBody(403);
        api.scriptQuery("BTCUSDT", 0, 0, "CANCELED", "o0");
        OrderReconciler reconciler = new OrderReconciler(api, "IOC", 1500);
        CycleState.Leg leg = leg("c-0", 0.001);

        reconciler.submitAndReconcile("BTCUSDT", Side.ASK, BTCUSDT, leg);

        assertEquals(1, api.queryOrderCalls, "a body we can't parse must fail closed into reconciliation");
        assertEquals(CycleState.LegStatus.ZERO_FILL, leg.status);
    }

    // ---- M5: a still-non-terminal status after the MAJ-03 cancel is ambiguous, not classifiable --

    @Test
    void stillNonTerminalAfterCancelIsUnknownNotClassifiedAsPartial() {
        // First query: resting (NEW/PARTIALLY_FILLED) -> OrderReconciler cancels and re-queries.
        // Second query (the "authoritative" re-query MAJ-03's own docs promise): STILL reports
        // PARTIALLY_FILLED -- the cancel raced a fill, or never took effect. This must never be fed
        // into classifyFill as if it were final.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        long partial = FixedPoint.fromDouble(0.0004);
        api.scriptQuerySequence("BTCUSDT",
                FakeMexcOrderApi.response(partial, FixedPoint.fromDouble(31.14), "PARTIALLY_FILLED", "o0"),
                FakeMexcOrderApi.response(partial, FixedPoint.fromDouble(31.14), "PARTIALLY_FILLED", "o0"));
        OrderReconciler reconciler = new OrderReconciler(api, "IOC", 1500);
        CycleState.Leg leg = leg("c-0", 0.001);

        reconciler.submitAndReconcile("BTCUSDT", Side.ASK, BTCUSDT, leg);

        assertEquals(1, api.cancelOrderCalls, "a resting order must still be cancelled");
        assertEquals(2, api.queryOrderCalls, "the MAJ-03 cancel-triggered re-query must still happen");
        assertEquals(CycleState.LegStatus.UNKNOWN, leg.status,
                "an order still resting after a cancel attempt is ambiguous -- must never be classified as PARTIAL");
    }

    @Test
    void terminalAfterCancelClassifiesNormally() {
        // The ordinary MAJ-03 case: resting, then genuinely terminal after the cancel takes effect.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        long partial = FixedPoint.fromDouble(0.0004);
        api.scriptQuerySequence("BTCUSDT",
                FakeMexcOrderApi.response(partial, FixedPoint.fromDouble(31.14), "PARTIALLY_FILLED", "o0"),
                FakeMexcOrderApi.response(partial, FixedPoint.fromDouble(31.14), "CANCELED", "o0"));
        OrderReconciler reconciler = new OrderReconciler(api, "IOC", 1500);
        CycleState.Leg leg = leg("c-0", 0.001);

        reconciler.submitAndReconcile("BTCUSDT", Side.ASK, BTCUSDT, leg);

        assertEquals(1, api.cancelOrderCalls);
        assertEquals(CycleState.LegStatus.PARTIAL, leg.status);
        assertEquals(partial, leg.executedBaseQtyFixed);
    }

    // ---- M6: a missing/unrecognized status must not skip the cancel -----------------------------

    @Test
    void missingStatusFieldTriggersCancelRatherThanBeingTrustedAsFinal() {
        // Third-pass review finding (M6): the previous isNonTerminal(null) == false meant a response
        // omitting `status` entirely was treated as "already final" -- skipping the cancel and
        // reading whatever executedQty happened to be there (often 0, misclassifying as ZERO_FILL
        // while a real order could still be resting on the matching engine).
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        long filled = FixedPoint.fromDouble(0.001);
        api.scriptQuerySequence("BTCUSDT",
                FakeMexcOrderApi.responseWithoutStatus(0, 0, "o0"),
                FakeMexcOrderApi.response(filled, FixedPoint.fromDouble(77.85), "FILLED", "o0"));
        OrderReconciler reconciler = new OrderReconciler(api, "IOC", 1500);
        CycleState.Leg leg = leg("c-0", 0.001);

        reconciler.submitAndReconcile("BTCUSDT", Side.ASK, BTCUSDT, leg);

        assertEquals(1, api.cancelOrderCalls, "a missing status must trigger the same cancel-and-verify path "
                + "a known-resting order gets, not be trusted as already final");
        assertEquals(CycleState.LegStatus.FILLED, leg.status, "the re-query found the true (filled) outcome");
    }

    @Test
    void unrecognizedStatusValueAlsoTriggersCancelRatherThanBeingTrustedAsFinal() {
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        long filled = FixedPoint.fromDouble(0.001);
        api.scriptQuerySequence("BTCUSDT",
                FakeMexcOrderApi.response(0, 0, "PENDING_NEW", "o0"), // not in KNOWN_TERMINAL_STATUSES
                FakeMexcOrderApi.response(filled, FixedPoint.fromDouble(77.85), "FILLED", "o0"));
        OrderReconciler reconciler = new OrderReconciler(api, "IOC", 1500);
        CycleState.Leg leg = leg("c-0", 0.001);

        reconciler.submitAndReconcile("BTCUSDT", Side.ASK, BTCUSDT, leg);

        assertTrue(api.cancelOrderCalls >= 1, "an unrecognized status must not be trusted as already terminal");
        assertEquals(CycleState.LegStatus.FILLED, leg.status);
    }

    @Test
    void knownTerminalStatusSkipsTheCancelEntirely() {
        // No regression: the common case (an order that reports FILLED on the first query) must
        // still take the fast path with no cancel call at all.
        FakeMexcOrderApi api = new FakeMexcOrderApi();
        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001), FixedPoint.fromDouble(77.85), "FILLED", "o0");
        OrderReconciler reconciler = new OrderReconciler(api, "IOC", 1500);
        CycleState.Leg leg = leg("c-0", 0.001);

        reconciler.submitAndReconcile("BTCUSDT", Side.ASK, BTCUSDT, leg);

        assertEquals(0, api.cancelOrderCalls);
        assertEquals(CycleState.LegStatus.FILLED, leg.status);
    }
}

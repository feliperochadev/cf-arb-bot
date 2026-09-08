package io.cfarb.exec;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Programmable {@link MexcOrderApi} test double. Each symbol gets a queue of scripted
 * {@code queryOrder} responses (status/executedQty/cummulativeQuoteQty as JSON) consumed in order,
 * ONE PER DISTINCT ORDER (keyed by {@code clientOrderId}, not merely by symbol) on that symbol --
 * mirroring the real place-then-reconcile flow without any network I/O. {@code placeOrder} always
 * "succeeds" with a synthetic orderId (unless {@link #makePlaceOrderFail} was called); tests drive
 * behavior entirely through the scripted {@code queryOrder} responses, since that is what
 * {@link OrderReconciler} actually reads fill data from (cf-arb-bot-review-plan.md's wire-contract
 * finding: the placement response itself never carries fill data on this venue).
 *
 * <p><b>Sticky-per-order re-query (REVIEW.md MAJ-03):</b> {@link OrderReconciler} now cancels and
 * re-queries a NON-terminal order (venue {@code status} {@code NEW}/{@code PARTIALLY_FILLED})
 * before classifying it, meaning the SAME {@code clientOrderId} can be queried twice. The first
 * query for a given {@code clientOrderId} pops the next scripted response off that SYMBOL's queue;
 * every subsequent query for that SAME {@code clientOrderId} replays the identical response
 * (simulating "nothing changed between the query and the cancel-triggered re-query") rather than
 * popping the next queued response, which would otherwise belong to a DIFFERENT order on the same
 * symbol (e.g. a leg's own re-query stealing the response scripted for its later unwind reversal,
 * which reverses the very same symbol).
 */
final class FakeMexcOrderApi implements MexcOrderApi {

    private final Map<String, Deque<String>> queryResponsesBySymbol = new HashMap<>();
    private final Map<String, String> stickyResponseByClientOrderId = new HashMap<>();
    private final Map<String, String> tradesResponseBySymbol = new HashMap<>();
    private boolean placeOrderFails;
    private int placeOrderFailStatus = 400;
    private boolean queryOrderFails;
    int placeOrderCalls;
    int queryOrderCalls;
    int listTradesCalls;
    int cancelOrderCalls;
    final java.util.List<String> placedParams = new java.util.ArrayList<>();

    /** Queue one scripted GET /api/v3/order response for the next NEW order placed on {@code symbol}. */
    void scriptQuery(String symbol, long executedQty, long cummulativeQuoteQty, String status, String orderId) {
        queryResponsesBySymbol.computeIfAbsent(symbol, k -> new ArrayDeque<>())
                .add("{\"executedQty\":\"" + toPlain(executedQty) + "\",\"cummulativeQuoteQty\":\""
                        + toPlain(cummulativeQuoteQty) + "\",\"status\":\"" + status + "\",\"orderId\":\""
                        + orderId + "\"}");
    }

    void scriptTrades(String symbol, double commission, String commissionAsset) {
        tradesResponseBySymbol.put(symbol, "[{\"commission\":\"" + commission + "\",\"commissionAsset\":\""
                + commissionAsset + "\"}]");
    }

    void makePlaceOrderFail() {
        this.placeOrderFails = true;
    }

    /** REVIEW.md MAJ-04: a placement rejection with a specific HTTP status -- 4xx must classify as
     * {@code REJECTED_PRESUBMIT} without a doomed query; a 5xx/timeout must still fall through to
     * reconciliation. Default (via {@link #makePlaceOrderFail}) is 400. */
    void makePlaceOrderFail(int httpStatus) {
        this.placeOrderFails = true;
        this.placeOrderFailStatus = httpStatus;
    }

    void makeQueryOrderFail() {
        this.queryOrderFails = true;
    }

    @Override
    public CompletableFuture<String> placeOrder(String queryString, long timeoutMs) {
        placeOrderCalls++;
        placedParams.add(queryString);
        if (placeOrderFails) {
            return CompletableFuture.failedFuture(
                    new MexcRestClient.OrderRejectedException(placeOrderFailStatus, "{\"msg\":\"rejected\"}"));
        }
        return CompletableFuture.completedFuture("{\"orderId\":\"synthetic-order-id\"}");
    }

    @Override
    public CompletableFuture<String> queryOrder(String symbol, String clientOrderId, long timeoutMs) {
        queryOrderCalls++;
        if (queryOrderFails) {
            return CompletableFuture.failedFuture(new RuntimeException("reconciliation network error"));
        }
        String sticky = stickyResponseByClientOrderId.get(clientOrderId);
        if (sticky != null) {
            return CompletableFuture.completedFuture(sticky);
        }
        Deque<String> responses = queryResponsesBySymbol.get(symbol);
        String resp;
        if (responses == null || responses.isEmpty()) {
            resp = "{\"executedQty\":\"0\",\"cummulativeQuoteQty\":\"0\",\"status\":\"CANCELED\",\"orderId\":\"none\"}";
        } else {
            resp = responses.poll();
        }
        stickyResponseByClientOrderId.put(clientOrderId, resp);
        return CompletableFuture.completedFuture(resp);
    }

    @Override
    public CompletableFuture<String> listTrades(String symbol, String orderId, long timeoutMs) {
        listTradesCalls++;
        String resp = tradesResponseBySymbol.get(symbol);
        return CompletableFuture.completedFuture(resp != null ? resp : "[]");
    }

    @Override
    public CompletableFuture<String> cancelOrder(String symbol, String clientOrderId, long timeoutMs) {
        cancelOrderCalls++;
        return CompletableFuture.completedFuture("{\"status\":\"CANCELED\"}");
    }

    @Override
    public String sign(String queryString) {
        return queryString + "&signature=fake";
    }

    private static String toPlain(long fixed1e8) {
        return io.cfarb.util.FixedPoint.toPlainString(fixed1e8, 8);
    }
}

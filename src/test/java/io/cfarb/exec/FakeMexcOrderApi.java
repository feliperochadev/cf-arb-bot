package io.cfarb.exec;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Programmable {@link MexcOrderApi} test double. Each symbol gets a queue of scripted
 * {@code queryOrder} responses (status/executedQty/cummulativeQuoteQty as JSON) consumed in order,
 * one per order placed on that symbol -- mirroring the real place-then-reconcile flow without any
 * network I/O. {@code placeOrder} always "succeeds" with a synthetic orderId; tests drive behavior
 * entirely through the scripted {@code queryOrder} responses, since that is what
 * {@link OrderReconciler} actually reads fill data from (cf-arb-bot-review-plan.md's wire-contract
 * finding: the placement response itself never carries fill data on this venue).
 */
final class FakeMexcOrderApi implements MexcOrderApi {

    private final Map<String, Deque<String>> queryResponsesBySymbol = new HashMap<>();
    private final Map<String, String> tradesResponseBySymbol = new HashMap<>();
    private boolean placeOrderFails;
    private boolean queryOrderFails;
    int placeOrderCalls;
    int queryOrderCalls;
    int listTradesCalls;
    final java.util.List<String> placedParams = new java.util.ArrayList<>();

    /** Queue one scripted GET /api/v3/order response for the next order placed on {@code symbol}. */
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

    void makeQueryOrderFail() {
        this.queryOrderFails = true;
    }

    @Override
    public CompletableFuture<String> placeOrder(String queryString, long timeoutMs) {
        placeOrderCalls++;
        placedParams.add(queryString);
        if (placeOrderFails) {
            return CompletableFuture.failedFuture(new MexcRestClient.OrderRejectedException(400, "{\"msg\":\"rejected\"}"));
        }
        return CompletableFuture.completedFuture("{\"orderId\":\"synthetic-order-id\"}");
    }

    @Override
    public CompletableFuture<String> queryOrder(String symbol, String clientOrderId, long timeoutMs) {
        queryOrderCalls++;
        if (queryOrderFails) {
            return CompletableFuture.failedFuture(new RuntimeException("reconciliation network error"));
        }
        Deque<String> responses = queryResponsesBySymbol.get(symbol);
        if (responses == null || responses.isEmpty()) {
            return CompletableFuture.completedFuture(
                    "{\"executedQty\":\"0\",\"cummulativeQuoteQty\":\"0\",\"status\":\"CANCELED\",\"orderId\":\"none\"}");
        }
        return CompletableFuture.completedFuture(responses.poll());
    }

    @Override
    public CompletableFuture<String> listTrades(String symbol, String orderId, long timeoutMs) {
        listTradesCalls++;
        String resp = tradesResponseBySymbol.get(symbol);
        return CompletableFuture.completedFuture(resp != null ? resp : "[]");
    }

    @Override
    public String sign(String queryString) {
        return queryString + "&signature=fake";
    }

    private static String toPlain(long fixed1e8) {
        return io.cfarb.util.FixedPoint.toPlainString(fixed1e8, 8);
    }
}

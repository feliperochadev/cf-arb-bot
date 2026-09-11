package io.cfarb.exec;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Programmable {@link MexcOrderApi} test double. Each symbol gets a queue of scripted
 * {@code queryOrder} response SEQUENCES (status/executedQty/cummulativeQuoteQty as JSON), one
 * sequence consumed per DISTINCT order (keyed by {@code clientOrderId}, not merely by symbol) on
 * that symbol -- mirroring the real place-then-reconcile flow without any network I/O.
 * {@code placeOrder} always "succeeds" with a synthetic orderId (unless {@link #makePlaceOrderFail}
 * was called); tests drive behavior entirely through the scripted {@code queryOrder} responses,
 * since that is what {@link OrderReconciler} actually reads fill data from (cf-arb-bot-review-plan.md's
 * wire-contract finding: the placement response itself never carries fill data on this venue).
 *
 * <p><b>Per-order response SEQUENCE (REVIEW.md MAJ-03, redesigned by the third-pass review):</b>
 * {@link OrderReconciler} cancels and re-queries a NON-terminal order (venue {@code status}
 * {@code NEW}/{@code PARTIALLY_FILLED}/anything unrecognized) before classifying it, meaning the
 * SAME {@code clientOrderId} can be queried twice. The first query for a given
 * {@code clientOrderId} pops the next scripted SEQUENCE off that symbol's queue and consumes its
 * first entry; every subsequent query for that SAME {@code clientOrderId} advances to the sequence's
 * NEXT entry (or repeats the last one once exhausted) rather than reusing a single fixed response --
 * this is what makes it possible to script "first query reports resting, the cancel-triggered
 * re-query reports something DIFFERENT" (a fill that landed between the two queries, or a genuinely
 * still-resting order the cancel failed to clear), which is exactly the race
 * {@code OrderReconciler}'s cancel-and-re-query exists to close. The single-response
 * {@link #scriptQuery} form is unchanged for every caller that doesn't care about the distinction
 * (nothing changes between the two queries) -- it is sugar for a one-entry sequence, which repeats
 * itself on a re-query the same way the old "sticky" behavior did.
 */
final class FakeMexcOrderApi implements MexcOrderApi {

    private final Map<String, Deque<Deque<String>>> queryResponsesBySymbol = new HashMap<>();
    private final Map<String, Deque<String>> sequenceByClientOrderId = new HashMap<>();
    private final Map<String, String> tradesResponseBySymbol = new HashMap<>();
    private boolean placeOrderFails;
    private int placeOrderFailStatus = 400;
    private String placeOrderFailBody = "{\"code\":-2010,\"msg\":\"Account has insufficient balance\"}";
    private boolean queryOrderFails;
    int placeOrderCalls;
    int queryOrderCalls;
    int listTradesCalls;
    int cancelOrderCalls;
    final java.util.List<String> placedParams = new java.util.ArrayList<>();

    /** Queue one scripted GET /api/v3/order response for the next NEW order placed on {@code symbol}
     * -- every query for that order (including a MAJ-03 cancel-triggered re-query) returns this
     * exact value, simulating "nothing changed" between the two queries. */
    void scriptQuery(String symbol, long executedQty, long cummulativeQuoteQty, String status, String orderId) {
        scriptQuerySequence(symbol, response(executedQty, cummulativeQuoteQty, status, orderId));
    }

    /** Queue a SEQUENCE of scripted responses for the next NEW order placed on {@code symbol}: the
     * first query returns {@code responses[0]}; the MAJ-03 cancel-triggered re-query (if the first
     * response reports a non-terminal status) returns {@code responses[1]}, etc. Once exhausted, the
     * LAST response repeats -- {@link OrderReconciler}'s own contract is at most one cancel + one
     * re-query per leg, so no script needs more than two entries today. */
    void scriptQuerySequence(String symbol, String... responses) {
        queryResponsesBySymbol.computeIfAbsent(symbol, k -> new ArrayDeque<>())
                .add(new ArrayDeque<>(List.of(responses)));
    }

    /** Package-visible so tests can build a scripted response's raw JSON directly (e.g. for
     * {@link #scriptQuerySequence}) without hand-writing the field layout. */
    static String response(long executedQty, long cummulativeQuoteQty, String status, String orderId) {
        return "{\"executedQty\":\"" + toPlain(executedQty) + "\",\"cummulativeQuoteQty\":\""
                + toPlain(cummulativeQuoteQty) + "\",\"status\":\"" + status + "\",\"orderId\":\""
                + orderId + "\"}";
    }

    /** A response with NO {@code status} field at all -- REVIEW.md MAJ-03's own gap (third-pass
     * review, M6): a response missing {@code status} must be treated as non-terminal (fail closed),
     * not silently skip the cancel path. */
    static String responseWithoutStatus(long executedQty, long cummulativeQuoteQty, String orderId) {
        return "{\"executedQty\":\"" + toPlain(executedQty) + "\",\"cummulativeQuoteQty\":\""
                + toPlain(cummulativeQuoteQty) + "\",\"orderId\":\"" + orderId + "\"}";
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
     * reconciliation. Default (via {@link #makePlaceOrderFail}) is 400 with a DEFINITIVE venue error
     * code (-2010, insufficient balance). */
    void makePlaceOrderFail(int httpStatus) {
        this.placeOrderFails = true;
        this.placeOrderFailStatus = httpStatus;
    }

    /** Third-pass review finding (M4): a 4xx placement rejection whose venue error code means the
     * OUTCOME is unknown (-1007, "Timeout waiting for response from backend server. Send status
     * unknown; execution status unknown.") -- must NOT classify as REJECTED_PRESUBMIT; it must fall
     * through to reconciliation like a 5xx/timeout would. */
    void makePlaceOrderFailAmbiguous(int httpStatus) {
        this.placeOrderFails = true;
        this.placeOrderFailStatus = httpStatus;
        this.placeOrderFailBody = "{\"code\":-1007,\"msg\":\"Timeout waiting for response from backend "
                + "server. Send status unknown; execution status unknown.\"}";
    }

    /** A 4xx placement rejection with a body {@link OrderReconciler} cannot parse a venue code out
     * of at all -- must fail closed (fall through to reconciliation), never assume "definitely
     * rejected" from a body it can't even read. */
    void makePlaceOrderFailUnparseableBody(int httpStatus) {
        this.placeOrderFails = true;
        this.placeOrderFailStatus = httpStatus;
        this.placeOrderFailBody = "not even json";
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
                    new MexcRestClient.OrderRejectedException(placeOrderFailStatus, placeOrderFailBody));
        }
        return CompletableFuture.completedFuture("{\"orderId\":\"synthetic-order-id\"}");
    }

    @Override
    public CompletableFuture<String> queryOrder(String symbol, String clientOrderId, long timeoutMs) {
        queryOrderCalls++;
        if (queryOrderFails) {
            return CompletableFuture.failedFuture(new RuntimeException("reconciliation network error"));
        }
        Deque<String> sequence = sequenceByClientOrderId.get(clientOrderId);
        if (sequence == null) {
            Deque<Deque<String>> bySymbol = queryResponsesBySymbol.get(symbol);
            Deque<String> scripted = (bySymbol == null || bySymbol.isEmpty())
                    ? new ArrayDeque<>(List.of("{\"executedQty\":\"0\",\"cummulativeQuoteQty\":\"0\","
                            + "\"status\":\"CANCELED\",\"orderId\":\"none\"}"))
                    : bySymbol.poll();
            sequence = scripted;
            sequenceByClientOrderId.put(clientOrderId, sequence);
        }
        // Repeat the LAST entry forever once exhausted; advance (poll) otherwise.
        String resp = sequence.size() > 1 ? sequence.poll() : sequence.peek();
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

    @Override
    public CompletableFuture<String> account(long timeoutMs) {
        return CompletableFuture.completedFuture("{\"balances\":[]}");
    }

    private static String toPlain(long fixed1e8) {
        return io.cfarb.util.FixedPoint.toPlainString(fixed1e8, 8);
    }
}

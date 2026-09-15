package io.cfarb.exec;

import java.util.concurrent.CompletableFuture;

/**
 * The three signed order-lifecycle calls {@link OrderReconciler} needs, factored out of
 * {@link MexcRestClient} so tests can stub the wire without a real Vert.x {@code WebClient}
 * (cf-arb-bot-review-plan.md "Tests to add": {@code UnwinderTest}/{@code CycleExecutorTest} against
 * a stubbed client). {@link MexcRestClient} is the only production implementation.
 */
public interface MexcOrderApi {

    /** See {@link MexcRestClient#placeOrder}. */
    CompletableFuture<String> placeOrder(String queryString, long timeoutMs);

    /** See {@link MexcRestClient#queryOrder}. */
    CompletableFuture<String> queryOrder(String symbol, String clientOrderId, long timeoutMs);

    /** See {@link MexcRestClient#listTrades}. */
    CompletableFuture<String> listTrades(String symbol, String orderId, long timeoutMs);

    /** See {@link MexcRestClient#cancelOrder} -- REVIEW.md MAJ-03: without this, a timed-out or
     * partially-filled order stays live on the matching engine while {@link Unwinder} runs against
     * inventory that can still change underneath it. */
    CompletableFuture<String> cancelOrder(String symbol, String clientOrderId, long timeoutMs);

    /** See {@link MexcRestClient#sign} -- dry-run's "sign and discard" path (Tier 1 step 1.9). */
    String sign(String queryString);

    /** See {@link MexcRestClient#account} -- PRE-LIVE-PLAN.md P1-4(b): {@code state.BalanceReconciler}'s
     * one boot-time call, live mode only. */
    CompletableFuture<String> account(long timeoutMs);
}

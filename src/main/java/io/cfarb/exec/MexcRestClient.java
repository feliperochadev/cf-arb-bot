package io.cfarb.exec;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import java.util.concurrent.CompletableFuture;

/**
 * Signed REST client for MEXC's authenticated spot endpoints. MEXC has NO WebSocket order-entry
 * API (cf-arb-bot-plan.md §5.4) — order placement is REST only, so a triangle is three sequential
 * dependent round trips (leg n+1's size depends on leg n's actual fill).
 *
 * <p><b>Wire-contract note (cf-arb-bot-review-plan.md Tier 1 step 1.5/1.6, confirmed against MEXC's
 * published spot v3 API reference 2026-09-07):</b> {@code POST /api/v3/order}'s response does NOT
 * carry {@code executedQty}/{@code cummulativeQuoteQty}/fills — only
 * {@code symbol,orderId,orderListId,price,origQty,type,side,transactTime}. A leg's actual fill
 * comes from a follow-up {@link #queryOrder}; commission detail comes from a further
 * {@link #listTrades}. Every leg is therefore place → query → (best-effort) trades, unconditionally,
 * not only on a timeout — the previous design's assumption that the placement response itself
 * carried fill data was never correct against this venue's actual response shape.
 *
 * <p><b>Connection reuse is a first-order performance lever here</b> — cf-arb-bot-plan.md §5.1:
 * "a cold TLS handshake costs ~60ms, so keep-alive/HTTP-2 connections must be pre-warmed and kept
 * hot." {@link WebClient} pools and reuses connections by default; {@link #warmUp} issues a cheap
 * unauthenticated call at startup (and can be called periodically) specifically to keep the pool's
 * connection alive across the long idle gaps between real trades (the observed fill rate is
 * single digits to tens per hour — cf-arb-bot-plan.md §2 — so an idle connection WILL be reclaimed
 * by intermediate infrastructure without this).
 *
 * <p>Security rules S1/S3 apply: the signer holds the secret, never this class; nothing here logs
 * a signed request or its parameters (only the venue-visible order id / http status, on success).
 */
public final class MexcRestClient implements MexcOrderApi {

    private final WebClient client;
    private final MexcSigner signer;
    private final String apiKey;
    private final String host;
    private final int port;
    private final long recvWindowMs;

    public MexcRestClient(Vertx vertx, String restUrl, String apiKey, MexcSigner signer, long recvWindowMs) {
        java.net.URI uri = java.net.URI.create(restUrl);
        this.host = uri.getHost();
        this.port = uri.getPort() != -1 ? uri.getPort() : 443;
        this.apiKey = apiKey;
        this.signer = signer;
        this.recvWindowMs = recvWindowMs;
        HttpClientOptions httpOpts = new HttpClientOptions()
                .setSsl(true)
                .setDefaultHost(host)
                .setDefaultPort(port)
                .setKeepAlive(true)
                .setPipelining(false) // MEXC order semantics require strict per-request ordering visibility; no HTTP pipelining
                .setConnectTimeout(3_000)
                .setMaxPoolSize(4); // small: 3 sequential legs per cycle, max-open-cycles=1
        WebClientOptions webOpts = new WebClientOptions(httpOpts);
        this.client = WebClient.create(vertx, webOpts);
    }

    /**
     * Build and sign the exact request {@link #placeOrder} would send, WITHOUT sending it — used
     * only by dry-run's "sign and discard" path (cf-arb-bot-review-plan.md Tier 1 step 1.9 / plan
     * §5.4 Phase 3) so serialization/signing cost is real in the latency histogram while no socket
     * is ever opened for it. Returns the fully-signed query string; the caller discards it.
     */
    public String sign(String queryString) {
        long timestamp = System.currentTimeMillis();
        String totalParams = queryString + "&recvWindow=" + recvWindowMs + "&timestamp=" + timestamp;
        String signature = signer.sign(totalParams);
        return totalParams + "&signature=" + signature;
    }

    /** Cheap unauthenticated call to keep the connection pool's socket warm across idle gaps. */
    public CompletableFuture<Void> warmUp() {
        CompletableFuture<Void> f = new CompletableFuture<>();
        client.get("/api/v3/ping").send(ar -> {
            if (ar.succeeded()) {
                f.complete(null);
            } else {
                f.completeExceptionally(ar.cause());
            }
        });
        return f;
    }

    /** Server time in epoch millis, for clock-skew sampling (cf-arb-bot-review-plan.md Tier 2
     * step 2.4). Unauthenticated, matching {@link #warmUp}. */
    public CompletableFuture<Long> serverTime() {
        CompletableFuture<Long> f = new CompletableFuture<>();
        client.get("/api/v3/time").send(ar -> {
            if (ar.failed()) {
                f.completeExceptionally(ar.cause());
                return;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(ar.result().bodyAsString());
                f.complete(node.get("serverTime").asLong());
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    /**
     * Place a signed order. {@code params} must NOT include timestamp/signature — those are
     * added here. Returns the raw JSON response body on HTTP 2xx; the caller ({@link CycleExecutor})
     * MUST NOT read fill quantities from this response — see the class javadoc's wire-contract
     * note — and must reconcile via {@link #queryOrder} regardless of what this call returns.
     * {@code timeoutMs} bounds both connect and response wait for this one request
     * (cf-arb-bot-review-plan.md Tier 1 step 1.6: the previously-unused {@code leg-timeout-ms}
     * config key).
     */
    public CompletableFuture<String> placeOrder(String queryString, long timeoutMs) {
        long timestamp = System.currentTimeMillis();
        String totalParams = queryString + "&recvWindow=" + recvWindowMs + "&timestamp=" + timestamp;
        String signature = signer.sign(totalParams);
        String fullQuery = totalParams + "&signature=" + signature;

        CompletableFuture<String> f = new CompletableFuture<>();
        client.post("/api/v3/order")
                .putHeader("X-MEXC-APIKEY", apiKey)
                .putHeader("Content-Type", "application/x-www-form-urlencoded")
                .timeout(timeoutMs)
                .sendBuffer(Buffer.buffer(fullQuery), ar -> {
                    if (ar.failed()) {
                        f.completeExceptionally(ar.cause());
                        return;
                    }
                    HttpResponse<Buffer> resp = ar.result();
                    if (resp.statusCode() / 100 != 2) {
                        f.completeExceptionally(new OrderRejectedException(resp.statusCode(), resp.bodyAsString()));
                        return;
                    }
                    f.complete(resp.bodyAsString());
                });
        return f;
    }

    /**
     * {@code GET /api/v3/order} by {@code origClientOrderId} — the authoritative source of
     * {@code executedQty}/{@code cummulativeQuoteQty}/{@code status}/{@code orderId} for a leg,
     * queried unconditionally after every {@link #placeOrder} attempt (success, rejection, or
     * timeout alike) since the client order id is generated before submission and is therefore
     * always known regardless of whether the placement response itself was ever received.
     */
    public CompletableFuture<String> queryOrder(String symbol, String clientOrderId, long timeoutMs) {
        return signedGet("/api/v3/order", "symbol=" + symbol + "&origClientOrderId=" + clientOrderId, timeoutMs);
    }

    /** {@code GET /api/v3/myTrades} filtered to one order id — the only endpoint that carries
     * per-fill {@code commission}/{@code commissionAsset}; best-effort, since a failure here must
     * not block the cycle on an otherwise-confirmed fill (the caller falls back to an estimated
     * fee, marked as such — cf-arb-bot-review-plan.md Tier 1 step 1.5). */
    public CompletableFuture<String> listTrades(String symbol, String orderId, long timeoutMs) {
        return signedGet("/api/v3/myTrades", "symbol=" + symbol + "&orderId=" + orderId, timeoutMs);
    }

    private CompletableFuture<String> signedGet(String path, String queryString, long timeoutMs) {
        long timestamp = System.currentTimeMillis();
        String totalParams = queryString + "&recvWindow=" + recvWindowMs + "&timestamp=" + timestamp;
        String signature = signer.sign(totalParams);
        String fullQuery = totalParams + "&signature=" + signature;

        CompletableFuture<String> f = new CompletableFuture<>();
        client.get(path + "?" + fullQuery)
                .putHeader("X-MEXC-APIKEY", apiKey)
                .timeout(timeoutMs)
                .send(ar -> {
                    if (ar.failed()) {
                        f.completeExceptionally(ar.cause());
                        return;
                    }
                    HttpResponse<Buffer> resp = ar.result();
                    if (resp.statusCode() / 100 != 2) {
                        f.completeExceptionally(new OrderRejectedException(resp.statusCode(), resp.bodyAsString()));
                        return;
                    }
                    f.complete(resp.bodyAsString());
                });
        return f;
    }

    /**
     * {@code DELETE /api/v3/order} by {@code origClientOrderId} -- REVIEW.md MAJ-03: cancels a
     * resting order before {@link OrderReconciler} treats it as terminal, closing the race where a
     * timed-out or partially-filled order fills the rest of its way while {@link Unwinder} is
     * already acting on the assumption it was done. Best-effort by design: MEXC returns an error if
     * the order already reached a terminal state (fully filled, already canceled, or never existed)
     * -- that is not a failure of this call, it just means there was nothing left to cancel, so the
     * caller treats any response (success or failure) the same way: re-query for the final state.
     */
    public CompletableFuture<String> cancelOrder(String symbol, String clientOrderId, long timeoutMs) {
        return signedDelete("/api/v3/order", "symbol=" + symbol + "&origClientOrderId=" + clientOrderId, timeoutMs);
    }

    private CompletableFuture<String> signedDelete(String path, String queryString, long timeoutMs) {
        long timestamp = System.currentTimeMillis();
        String totalParams = queryString + "&recvWindow=" + recvWindowMs + "&timestamp=" + timestamp;
        String signature = signer.sign(totalParams);
        String fullQuery = totalParams + "&signature=" + signature;

        CompletableFuture<String> f = new CompletableFuture<>();
        client.delete(path + "?" + fullQuery)
                .putHeader("X-MEXC-APIKEY", apiKey)
                .timeout(timeoutMs)
                .send(ar -> {
                    if (ar.failed()) {
                        f.completeExceptionally(ar.cause());
                        return;
                    }
                    HttpResponse<Buffer> resp = ar.result();
                    if (resp.statusCode() / 100 != 2) {
                        f.completeExceptionally(new OrderRejectedException(resp.statusCode(), resp.bodyAsString()));
                        return;
                    }
                    f.complete(resp.bodyAsString());
                });
        return f;
    }

    /** Thrown when MEXC responds with a non-2xx to a signed request. Never includes the request
     * body/signature in its message (S3). */
    public static final class OrderRejectedException extends RuntimeException {
        public final int statusCode;
        public final String responseBody;

        public OrderRejectedException(int statusCode, String responseBody) {
            super("MEXC request rejected: HTTP " + statusCode);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }
}

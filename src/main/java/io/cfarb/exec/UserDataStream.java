package io.cfarb.exec;

import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.client.WebClient;
import org.jboss.logging.Logger;

/**
 * Best-effort supplementary fill confirmation via MEXC's private {@code listenKey} user-data
 * stream ({@code spot@private.orders.v3.api} / {@code spot@private.deals.v3.api}) — NOT required
 * for correctness: {@link CycleExecutor} already gets its authoritative fill data directly from
 * each order placement's REST response (cf-arb-bot-plan.md §5.4: "whichever lands first"). This
 * class exists to close that race in the bot's favor when the user-data push beats the REST
 * response, and to give the journal/API a secondary confirmation signal.
 *
 * <p><b>Unverified against the live endpoint.</b> The listenKey lifecycle here
 * (POST /api/v3/userDataStream to obtain one, PUT to keep it alive every ~30 minutes, connect to
 * {@code wss://wbs-api.mexc.com/ws?listenKey=<key>}) follows the Binance-family convention MEXC's
 * spot v3 API is modeled on, but per {@code recorder-service}'s hard-won non-negotiable #4 —
 * "Connected is not working... any new MEXC channel must be probed live before being trusted" —
 * this MUST be probed against the real endpoint in Phase 1 before anything downstream relies on it.
 * Until then, treat this class as present-but-unverified; {@link CycleExecutor}'s REST-response
 * path does not depend on it.
 */
public final class UserDataStream {

    private static final Logger LOG = Logger.getLogger(UserDataStream.class);
    private static final long LISTEN_KEY_REFRESH_MS = 25 * 60 * 1000L; // MEXC listenKeys expire at 60min; refresh well before

    private final Vertx vertx;
    private final WebClient restClient;
    private final String apiKey;
    private final String wsBaseUrl;

    private volatile String listenKey;
    private volatile boolean running;
    // REVIEW.md MED-04: created once in start() and reused across every reconnect -- the previous
    // version allocated a fresh WebSocketClient (and its underlying Netty channel pool) on every
    // single reconnect attempt without ever closing the old one, leaking a client (and file
    // descriptors) per attempt over an extended run with unstable connectivity.
    private volatile WebSocketClient wsClient;

    public UserDataStream(Vertx vertx, WebClient restClient, String apiKey, String wsBaseUrl) {
        this.vertx = vertx;
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.wsBaseUrl = wsBaseUrl;
    }

    public void start() {
        running = true;
        WebSocketClientOptions opts = new WebSocketClientOptions().setSsl(true).setTcpKeepAlive(true);
        this.wsClient = vertx.createWebSocketClient(opts);
        obtainListenKeyAndConnect();
        vertx.setPeriodic(LISTEN_KEY_REFRESH_MS, id -> {
            if (running) {
                refreshListenKey();
            }
        });
    }

    public void stop() {
        running = false;
        WebSocketClient c = wsClient;
        if (c != null) {
            c.close();
        }
    }

    private void obtainListenKeyAndConnect() {
        restClient.post("/api/v3/userDataStream")
                .putHeader("X-MEXC-APIKEY", apiKey)
                .send(ar -> {
                    if (ar.failed()) {
                        LOG.warnf("[user-data] failed to obtain listenKey: %s -- fill confirmation "
                                + "will rely solely on REST responses", ar.cause().toString());
                        return;
                    }
                    try {
                        com.fasterxml.jackson.databind.JsonNode node =
                                new com.fasterxml.jackson.databind.ObjectMapper().readTree(ar.result().bodyAsString());
                        this.listenKey = node.get("listenKey").asText();
                        connectWs();
                    } catch (Exception e) {
                        LOG.warnf("[user-data] could not parse listenKey response: %s", e.toString());
                    }
                });
    }

    private void refreshListenKey() {
        if (listenKey == null) {
            obtainListenKeyAndConnect();
            return;
        }
        // REVIEW.md MED-05: the previous version sent listenKey as an unlabeled form body with no
        // Content-Type header, which MEXC's PUT /api/v3/userDataStream does not document accepting
        // -- send it as a query parameter instead, matching every other signed/keyed request this
        // codebase makes to this venue (MexcRestClient's endpoints all pass their params in the URL).
        restClient.put("/api/v3/userDataStream?listenKey=" + listenKey)
                .putHeader("X-MEXC-APIKEY", apiKey)
                .send(ar -> {
                    if (ar.failed()) {
                        LOG.warnf("[user-data] listenKey refresh failed: %s", ar.cause().toString());
                    }
                });
    }

    private void connectWs() {
        java.net.URI uri = java.net.URI.create(wsBaseUrl + "?listenKey=" + listenKey);
        WebSocketConnectOptions connect = new WebSocketConnectOptions()
                .setHost(uri.getHost()).setPort(443).setURI(uri.getRawPath() + "?" + uri.getRawQuery()).setSsl(true);
        wsClient.connect(connect).onSuccess(ws -> {
            LOG.infof("[user-data] connected");
            ws.frameHandler(frame -> {
                // Best-effort: log only. Wiring this into CycleExecutor's fill-confirmation path
                // is deferred to Phase 1 pending live verification (see class javadoc).
                if (frame.isBinary() || frame.isText()) {
                    LOG.debugf("[user-data] frame: %d bytes", frame.binaryData().length());
                }
            });
            ws.closeHandler(v -> {
                if (running) {
                    vertx.setTimer(5_000, id -> connectWs());
                }
            });
        }).onFailure(t -> {
            LOG.warnf("[user-data] connect failed: %s", t.toString());
            if (running) {
                vertx.setTimer(5_000, id -> connectWs());
            }
        });
    }
}

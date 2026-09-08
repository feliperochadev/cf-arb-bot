package io.cfarb.feed;

import io.cfarb.book.BookRegistry;
import io.cfarb.metrics.BotMetrics;
import io.cfarb.risk.KillSwitch;
import io.cfarb.strategy.OpportunityDetector;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebSocketFrame;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * The single MEXC depth WebSocket connection this bot uses — one connection for all 9 configured
 * symbols (well under MEXC's 30-streams-per-connection cap), decoding straight into
 * {@link BookRegistry} and invoking {@link OpportunityDetector} on every update, all on the Netty
 * event-loop thread with zero allocation on the steady-state path (rule R1). Ported scaffolding
 * from {@code recorder-service.capture.VenueConnection} (WS options, capped exponential backoff,
 * fragmented-frame reassembly, reconnect-churn tracking) — cf-arb-bot-plan.md §4's "port
 * near-verbatim" list — with the payload handling completely rewritten: recorder-service memcpys
 * to a ring and never parses (its "one rule"); this bot decodes in place and detects opportunities
 * (cf-arb-bot-plan.md §5.1's explicitly renegotiated exception to that rule).
 */
public final class MexcWsClient {

    private static final Logger LOG = Logger.getLogger(MexcWsClient.class);
    private static final int MAX_MESSAGE_BYTES = 4 << 20;
    private static final long CHURN_WINDOW_MS = 300_000;
    private static final int CHURN_THRESHOLD = 5;

    private final Vertx vertx;
    private final String wsUrl;
    private final List<String> subscribeMessages;
    private final BookRegistry books;
    private final OpportunityDetector detector;
    private final BotMetrics metrics;
    private final KillSwitch killSwitch;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile WebSocketClient client;
    private volatile WebSocket socket;
    private volatile boolean connected;
    private int reconnectAttempts;
    private long keepaliveTimerId = -1;
    private Buffer continuationBuffer;
    private final ArrayDeque<Long> recentConnectMillis = new ArrayDeque<>();

    // Reusable decode buffers -- allocated once per connection, per MexcDepthDecoder's contract.
    private final ProtobufWalker.Cursor topCursor = new ProtobufWalker.Cursor();
    private final ProtobufWalker.Cursor subCursor = new ProtobufWalker.Cursor();
    private final ProtobufWalker.Cursor levelCursor = new ProtobufWalker.Cursor();
    private final MexcDepthDecoder.DepthFrame depthFrame = new MexcDepthDecoder.DepthFrame();

    public MexcWsClient(Vertx vertx, String wsUrl, List<String> subscribeMessages, BookRegistry books,
                         OpportunityDetector detector, BotMetrics metrics, KillSwitch killSwitch) {
        this.vertx = vertx;
        this.wsUrl = wsUrl;
        this.subscribeMessages = subscribeMessages;
        this.books = books;
        this.detector = detector;
        this.metrics = metrics;
        this.killSwitch = killSwitch;
    }

    public void start() {
        WebSocketClientOptions options = new WebSocketClientOptions()
                .setTcpNoDelay(true)
                .setTcpKeepAlive(true)
                .setConnectTimeout(5_000)
                .setSsl(true)
                .setTryUsePerMessageCompression(false)
                .setMaxMessageSize(MAX_MESSAGE_BYTES);
        this.client = vertx.createWebSocketClient(options);
        connect();
    }

    public void stop() {
        closed.set(true);
        if (socket != null) {
            socket.close();
        }
    }

    public boolean isConnected() {
        return connected;
    }

    /** REVIEW.md MAJ-06: close the current socket to run through the EXISTING recovery path
     * ({@code closeHandler} -> {@code books.resetAll()} -> {@code scheduleReconnect()}) rather than
     * the feed watchdog's previous behavior of latching the kill switch permanently on a connection
     * that {@code isConnected()} still reports as alive (a half-open socket, a silently stalled
     * proxy). A no-op if a reconnect is already in flight -- {@code closeHandler} already flips
     * {@link #connected} false, so a second watchdog tick before the new connection lands does not
     * double-fire this. */
    public void forceReconnect() {
        if (!connected) {
            return; // already disconnected/reconnecting -- closeHandler's path is already running
        }
        WebSocket s = socket;
        if (s != null) {
            s.close();
        }
    }

    /** cf-arb-bot-review-plan.md Tier 1 step 1.7: prune on READ, not only on connect -- the
     * previous version only pruned {@link #recentConnectMillis} inside {@link #recordConnectForChurn},
     * so a churn flag set by a burst of reconnects could stay latched {@code true} indefinitely once
     * the connection settled and stopped generating new connect events to trigger a prune.
     * {@code synchronized} because this is read from the Quarkus HTTP worker thread
     * ({@code api.BotApiResource} via {@code BotService#wsClientChurning}) while
     * {@link #recordConnectForChurn} mutates the same deque from the Netty event-loop thread --
     * {@link ArrayDeque} is not thread-safe, and this is a rare connection-lifecycle path, not the
     * market-data tick path, so a brief lock here does not touch the hot-path no-locks rule. */
    public boolean isChurning() {
        synchronized (recentConnectMillis) {
            long now = System.currentTimeMillis();
            while (!recentConnectMillis.isEmpty() && now - recentConnectMillis.peekFirst() > CHURN_WINDOW_MS) {
                recentConnectMillis.pollFirst();
            }
            return recentConnectMillis.size() >= CHURN_THRESHOLD;
        }
    }

    private void connect() {
        if (closed.get()) {
            return;
        }
        URI uri = URI.create(wsUrl);
        int port = uri.getPort() != -1 ? uri.getPort() : 443;
        WebSocketConnectOptions connect = new WebSocketConnectOptions()
                .setHost(uri.getHost()).setPort(port).setURI(uri.getPath()).setSsl(true);

        LOG.infof("connecting to %s", wsUrl);
        client.connect(connect).onSuccess(ws -> {
            this.socket = ws;
            this.connected = true;
            this.reconnectAttempts = 0;
            recordConnectForChurn();
            LOG.infof("connected");

            ws.frameHandler(this::onWsFrame);
            ws.exceptionHandler(t -> LOG.warnf("socket error: %s", t.toString()));
            ws.closeHandler(v -> {
                this.connected = false;
                LOG.warnf("connection closed");
                cancelKeepalive();
                // cf-arb-bot-review-plan.md Tier 1 step 1.7: reset every book on disconnect. Without
                // this, the old ladder and version-chain survive across a reconnect gap; fresh diffs
                // could merge into a stale book, and the gap-detector in L2Book.apply only catches
                // this if the venue's version fields are present and contiguous.
                books.resetAll();
                scheduleReconnect();
            });

            // Reset again immediately before re-subscribing (belt and suspenders: a connection that
            // was force-closed by the venue rather than going through closeHandler's normal path
            // should not resume trusting whatever was in the books beforehand).
            books.resetAll();
            sendSubscribeMessages(ws);
            startKeepalive(ws);
        }).onFailure(t -> {
            LOG.warnf("connect failed: %s", t.toString());
            scheduleReconnect();
        });
    }

    private void sendSubscribeMessages(WebSocket ws) {
        for (String msg : subscribeMessages) {
            ws.writeTextMessage(msg);
        }
    }

    private void startKeepalive(WebSocket ws) {
        keepaliveTimerId = vertx.setPeriodic(MexcProtocol.KEEPALIVE_INTERVAL_MS, id -> {
            if (connected) {
                ws.writeTextMessage(MexcProtocol.KEEPALIVE_MESSAGE);
            }
        });
    }

    private void cancelKeepalive() {
        if (keepaliveTimerId != -1) {
            vertx.cancelTimer(keepaliveTimerId);
            keepaliveTimerId = -1;
        }
    }

    private void scheduleReconnect() {
        if (closed.get()) {
            return;
        }
        long delayMs = Math.min(30_000, 500L << Math.min(reconnectAttempts++, 6));
        vertx.setTimer(delayMs, id -> connect());
    }

    private void recordConnectForChurn() {
        synchronized (recentConnectMillis) {
            long now = System.currentTimeMillis();
            recentConnectMillis.addLast(now);
            while (!recentConnectMillis.isEmpty() && now - recentConnectMillis.peekFirst() > CHURN_WINDOW_MS) {
                recentConnectMillis.pollFirst();
            }
        }
    }

    /** Frame entry — clock stamp first (S14: local nanoTime, never venue-supplied), matching the
     * VenueConnection/ExchangeFeed convention this is ported from. */
    private void onWsFrame(WebSocketFrame frame) {
        if (frame.isClose()) {
            return;
        }
        long nowNanos = System.nanoTime();
        Buffer data = frame.binaryData();

        Buffer full;
        if (!frame.isFinal()) {
            long pending = (continuationBuffer == null ? 0 : continuationBuffer.length()) + data.length();
            if (pending > MAX_MESSAGE_BYTES) {
                continuationBuffer = null;
                metrics.recordFrameDropped();
                return;
            }
            continuationBuffer = continuationBuffer == null ? Buffer.buffer().appendBuffer(data)
                    : continuationBuffer.appendBuffer(data);
            return;
        }
        if (continuationBuffer != null) {
            full = continuationBuffer.appendBuffer(data);
            continuationBuffer = null;
        } else {
            full = data;
        }

        metrics.recordFrameReceived();
        byte[] bytes = full.getBytes();
        MexcProtocol.FrameClass frameClass = MexcProtocol.classifyFrame(bytes, bytes.length);
        switch (frameClass) {
            case SUBSCRIPTION_REJECTED -> {
                LOG.errorf("subscription rejected: %s", new String(bytes, 0, Math.min(bytes.length, 400)));
                killSwitch.recordFeedUnhealthy("subscription-rejected");
            }
            case KEEPALIVE_REPLY, CONTROL_ACK -> {
                // liveness only, never touches a book
            }
            case MARKET_DATA -> handleMarketData(bytes, nowNanos);
        }
    }

    private void handleMarketData(byte[] bytes, long nowNanos) {
        long decodeStart = System.nanoTime();
        boolean ok = MexcDepthDecoder.decode(bytes, 0, bytes.length, topCursor, subCursor, levelCursor, depthFrame);
        if (!ok) {
            return; // not a genuine depth push (shouldn't happen given classifyFrame, but stay defensive)
        }
        int symbolIndex = books.applyFrame(depthFrame, nowNanos);
        if (symbolIndex < 0) {
            return; // symbol not in our configured universe
        }
        metrics.recordFrameToDecisionNanos(System.nanoTime() - decodeStart);
        detector.onBookUpdated(symbolIndex, nowNanos);
    }
}

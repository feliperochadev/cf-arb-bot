package io.cfarb;

import io.cfarb.book.BookRegistry;
import io.cfarb.book.L2Book;
import io.cfarb.config.BotConfig;
import io.cfarb.config.SymbolFilterLoader;
import io.cfarb.exec.CycleExecutor;
import io.cfarb.exec.MexcRestClient;
import io.cfarb.exec.MexcSigner;
import io.cfarb.exec.Unwinder;
import io.cfarb.exec.UserDataStream;
import io.cfarb.feed.MexcProtocol;
import io.cfarb.feed.MexcWsClient;
import io.cfarb.graph.TriangleRegistry;
import io.cfarb.journal.EventJournal;
import io.cfarb.journal.JournalEvents;
import io.cfarb.metrics.BotMetrics;
import io.cfarb.model.OrderIntent;
import io.cfarb.model.SymbolFilter;
import io.cfarb.risk.KillSwitch;
import io.cfarb.risk.RiskGates;
import io.cfarb.state.Portfolio;
import io.cfarb.strategy.OpportunityDetector;
import io.cfarb.util.FixedPoint;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jctools.queues.SpscArrayQueue;

/**
 * Application lifecycle owner — wires config, book registry, triangle registry, risk gates, the
 * detector, the executor, and the WebSocket client together, then starts/stops them with the
 * Quarkus application lifecycle. Mirrors {@code cf-trader.fetcher.FetcherService} /
 * {@code recorder-service.capture.RecorderService}'s role.
 */
@ApplicationScoped
public class BotService {

    private static final Logger LOG = Logger.getLogger(BotService.class);
    private static final long WARMUP_UPDATES = 50; // fewer than book.py's 200: 9 thin cross-pairs
    private static final long WARMUP_SECONDS = 30; // update far more slowly than BTC/ETH majors
    private static final long WARMUP_PERIOD_MS = 60_000; // rest-client keep-warm + clock-skew sample cadence
    private static final long WATCHDOG_PERIOD_MS = 5_000; // feed staleness watchdog cadence
    /** How long NOTHING may arrive across the ENTIRE feed before it's considered dead at the
     * connection level -- deliberately much looser than RiskGates' per-triangle max-book-age-ms
     * (250ms default), which gates individual fire decisions and is expected to trip routinely for
     * a momentarily-quiet illiquid symbol. This watchdog exists for the case closeHandler never
     * fires at all (a half-open TCP connection, a silently stalled proxy) -- it should only fire
     * when literally every configured symbol has gone quiet at once. Raised from 5s to 15s by
     * REVIEW.md's second pass -- 5s left very little margin above ordinary feed jitter before
     * declaring the WHOLE connection dead. */
    private static final long CONNECTION_DEAD_THRESHOLD_NANOS = 15_000_000_000L;
    /** REVIEW.md MAJ-06: consecutive watchdog detections (each WATCHDOG_PERIOD_MS apart) that a
     * forced reconnect failed to clear the staleness before the kill switch is finally tripped as a
     * last resort -- "reconnection failing repeatedly," not "the connection was briefly quiet." */
    private static final int FEED_DEAD_TRIP_THRESHOLD = 3;
    private static final long LATENCY_SNAPSHOT_PERIOD_MS = 60_000;
    /** Dry-run never opens a socket or sends a request, but MexcSigner requires a non-empty secret
     * to construct -- cf-arb-bot-review-plan.md Tier 1 step 1.9. This is not a real credential and
     * is never used to sign anything that leaves the process. */
    private static final String DRY_RUN_DUMMY_SECRET = "dry-run-never-sent-0000000000000000";

    @Inject
    BotConfig config;
    @Inject
    Vertx vertx;
    @Inject
    BotMetrics metrics;

    private BookRegistry books;
    private TriangleRegistry triangles;
    private Portfolio portfolio;
    private KillSwitch killSwitch;
    private RiskGates riskGates;
    private EventJournal journal;
    private MexcWsClient wsClient;
    private CycleExecutor executor;
    private UserDataStream userDataStream;
    private MexcRestClient restClient;

    private volatile long clockSkewNanos;
    private long clockSkewToleranceNanos;
    private int consecutiveFeedDeadDetections; // Vert.x event-loop thread only -- single timer callback

    void onStart(@Observes StartupEvent ev) {
        logStartupSafetyBanner();

        Map<String, SymbolFilter> filters = loadFilters();
        this.books = new BookRegistry(config.symbols(), WARMUP_UPDATES, WARMUP_SECONDS);
        this.triangles = new TriangleRegistry(config, books, filters);
        LOG.infof("loaded %d triangles across %d symbols", triangles.triangleCount(), books.symbolCount());

        boolean dryRun = config.dryRun();
        if (!dryRun) {
            validateOrderType(filters);
        }

        // cf-arb-bot-review-plan.md Tier 2 step 2.5: fail closed on a non-positive capital
        // configuration instead of silently trading on a nonsensical value.
        double seedUsd = config.capital().seedUsd();
        double equityFloorUsd = config.risk().equityFloorUsd();
        if (seedUsd <= 0) {
            throw new IllegalStateException("cf-bot.capital.seed-usd must be > 0, got " + seedUsd);
        }
        if (equityFloorUsd <= 0) {
            throw new IllegalStateException("cf-bot.risk.equity-floor-usd must be > 0, got " + equityFloorUsd);
        }

        long seedFixed = FixedPoint.fromDouble(seedUsd);
        this.portfolio = new Portfolio(seedFixed);
        this.killSwitch = new KillSwitch(portfolio, FixedPoint.fromDouble(equityFloorUsd),
                config.risk().maxConsecutiveFailures());
        this.riskGates = new RiskGates(config.risk(), config.strategy(), config.exec(),
                triangles.triangleCount(), killSwitch, dryRun);
        this.clockSkewToleranceNanos = riskGates.clockSkewToleranceNanos();
        if (riskGates.notionalWasClamped) {
            LOG.warnf("cf-bot.risk.max-notional-usd=%.2f exceeds the absolute ceiling -- clamped to %.2f "
                            + "(security rule S6: hard caps enforced in code, not only config)",
                    config.risk().maxNotionalUsd(), riskGates.effectiveMaxNotionalUsd);
        }
        LOG.infof("effective risk limits: maxNotionalUsd=%.2f maxOpenCycles=%d maxCyclesPerMinute=%d "
                        + "cycleCooldownMs=%d maxConsecutiveFailures=%d equityFloorUsd=%.2f",
                riskGates.effectiveMaxNotionalUsd, config.risk().maxOpenCycles(), config.risk().maxCyclesPerMinute(),
                config.risk().cycleCooldownMs(), config.risk().maxConsecutiveFailures(), equityFloorUsd);

        this.journal = new EventJournal(Path.of(config.journal().dir()), metrics);
        journal.start();

        wireKillSwitchTripListener();
        // cf-arb-bot-review-plan.md Tier 1 step 1.8: check the equity floor at startup, before the
        // feed even connects -- a seed already at or below the floor must never trade.
        killSwitch.checkEquityFloor();

        SpscArrayQueue<OrderIntent> orderQueue = new SpscArrayQueue<>(256);
        OpportunityDetector detector = new OpportunityDetector(books, triangles, riskGates, portfolio,
                metrics, journal, orderQueue, config.strategy().minNetBps(), config.strategy().slippageBufferBps(),
                config.capital().compound(), config.journal().rejectSampleMs());

        // cf-arb-bot-review-plan.md Tier 1 step 1.9: MexcRestClient (and therefore the signer) is
        // now constructed in BOTH modes, so dry-run can build and sign every request through the
        // real code path and discard it (realistic latency histogram) without ever opening a
        // socket for it. Only live mode reads real credentials from the environment.
        String apiKey;
        MexcSigner signer;
        if (dryRun) {
            apiKey = "dry-run";
            signer = new MexcSigner(DRY_RUN_DUMMY_SECRET);
        } else {
            apiKey = requireEnv("MEXC_API_KEY");
            signer = new MexcSigner(requireEnv("MEXC_API_SECRET"));
        }
        this.restClient = new MexcRestClient(vertx, config.venue().restUrl(), apiKey, signer, config.exec().recvWindowMs());

        Unwinder unwinder = null;
        if (!dryRun) {
            unwinder = new Unwinder(restClient, config.exec().orderType(), config.exec().legTimeoutMs(),
                    books, config.exec().unwindCrossBps());

            WebClientOptions userDataOpts = new WebClientOptions().setSsl(true).setDefaultHost(
                    URI.create(config.venue().restUrl()).getHost()).setDefaultPort(443);
            this.userDataStream = new UserDataStream(vertx, WebClient.create(vertx, userDataOpts), apiKey,
                    config.venue().wsUrl());
            userDataStream.start();
        }

        this.executor = new CycleExecutor(orderQueue, triangles, riskGates, killSwitch, portfolio,
                metrics, journal, dryRun, restClient, unwinder, config.exec().orderType(),
                config.exec().legTimeoutMs(), config.exec().maxIntentAgeMs());
        executor.start();

        List<String> subscribeMessages = MexcProtocol.subscribeMessages(config.venue().depthChannel(), config.symbols());
        this.wsClient = new MexcWsClient(vertx, config.venue().wsUrl(), subscribeMessages, books, detector,
                metrics, killSwitch);
        wsClient.start();

        scheduleWarmUpAndClockSkew();
        scheduleFeedWatchdog();
        scheduleLatencySnapshots();

        LOG.infof("cf-arb-bot started: dryRun=%s seed=$%.2f floor=$%.2f triangles=%d",
                dryRun, config.capital().seedUsd(), config.risk().equityFloorUsd(), triangles.triangleCount());
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (wsClient != null) wsClient.stop();
        if (executor != null) executor.stop();
        if (userDataStream != null) userDataStream.stop();
        if (journal != null) journal.stop();
    }

    /** cf-arb-bot-review-plan.md Tier 1 step 1.8: give the kill switch's trip a real effect --
     * previously it only flipped a boolean with no journal event, metric, or operator-visible
     * signal. */
    private void wireKillSwitchTripListener() {
        killSwitch.setTripListener(reason -> {
            metrics.recordRiskTrip();
            journal.write(JournalEvents.riskTrip(reason, portfolio.equity()));
            LOG.errorf("*** KILL SWITCH TRIPPED *** reason=%s equity=$%.2f -- trading halted, "
                    + "operator review required (restart after review to clear)", reason,
                    FixedPoint.toDouble(portfolio.equity()));
        });
    }

    /** MEXC's documented {@code type} ENUM for {@code POST /api/v3/order} (LIMIT/MARKET/
     * LIMIT_MAKER/IOC/FOK) -- see BotConfig.ExecConfig#orderType's javadoc for how this was
     * confirmed and why the first remediation pass's IMMEDIATE_OR_CANCEL default was wrong. */
    private static final java.util.Set<String> MEXC_DOCUMENTED_ORDER_TYPES =
            java.util.Set.of("LIMIT", "MARKET", "LIMIT_MAKER", "IOC", "FOK");
    /** {@code exchangeInfo}'s per-symbol {@code order_types} never lists these two for ANY MEXC
     * symbol (confirmed live across all 2074 spot symbols) -- membership can never be satisfied, so
     * this bot cannot use exchangeInfo to verify them and settles for a loud warning instead of a
     * boot failure. See BotConfig.ExecConfig#orderType's javadoc. */
    private static final java.util.Set<String> UNVERIFIABLE_VIA_EXCHANGE_INFO = java.util.Set.of("IOC", "FOK");

    /** cf-arb-bot-review-plan.md's second independent review pass (REVIEW.md MAJ-01): fail closed
     * in live mode on an order type MEXC's API does not even define, and require every configured
     * symbol to support LIMIT (the priced-order capability every execution path here depends on,
     * regardless of the exact {@code type} value used). If the configured type IS one
     * {@code exchangeInfo} enumerates per-symbol (LIMIT/MARKET/LIMIT_MAKER), require membership as
     * before. If it is IOC/FOK -- which exchangeInfo never enumerates for any MEXC symbol -- log a
     * prominent warning that acceptance is unverified until the credentialed 1-USDT live probe runs,
     * and continue rather than refusing to start (the previous pass's mistake here was papering over
     * a genuinely unresolved wire-format question by "fixing" the default to an even-less-correct
     * value; the honest state is "unverified", not "definitely rejected"). */
    private void validateOrderType(Map<String, SymbolFilter> filters) {
        String orderType = config.exec().orderType();
        if (!MEXC_DOCUMENTED_ORDER_TYPES.contains(orderType)) {
            throw new IllegalStateException("cf-bot.exec.order-type=" + orderType + " is not one of "
                    + "MEXC's documented order types " + MEXC_DOCUMENTED_ORDER_TYPES + " -- refusing to "
                    + "start in live mode (security rule S5); see BotConfig.ExecConfig#orderType's javadoc");
        }
        boolean unverifiable = UNVERIFIABLE_VIA_EXCHANGE_INFO.contains(orderType);
        for (Map.Entry<String, BotConfig.TriangleConfig> e : config.triangles().entrySet()) {
            if (!e.getValue().enabled()) {
                continue;
            }
            for (String leg : e.getValue().legs()) {
                String symbol = leg.split(":")[0];
                SymbolFilter filter = filters.get(symbol);
                if (filter == null) {
                    continue;
                }
                if (!filter.orderTypes().contains("LIMIT")) {
                    throw new IllegalStateException("MEXC symbol '" + symbol + "' does not advertise LIMIT "
                            + "support (venue advertises " + filter.orderTypes() + ") -- refusing to start "
                            + "in live mode (security rule S5)");
                }
                if (unverifiable) {
                    LOG.warnf("*** cf-bot.exec.order-type=%s for symbol '%s' is NOT verifiable against "
                                    + "GET /api/v3/exchangeInfo (venue never lists IOC/FOK in any symbol's "
                                    + "order_types) -- acceptance is UNCONFIRMED until the credentialed "
                                    + "1-USDT live probe runs; see BotConfig.ExecConfig#orderType's javadoc",
                            orderType, symbol);
                } else if (!filter.orderTypes().contains(orderType)) {
                    throw new IllegalStateException("cf-bot.exec.order-type=" + orderType + " is not supported "
                            + "by MEXC for symbol '" + symbol + "' (venue advertises " + filter.orderTypes()
                            + ") -- refusing to start in live mode (security rule S5)");
                }
            }
        }
    }

    /** cf-arb-bot-review-plan.md (second pass) REVIEW.md MED-02: the previous computation captured
     * {@code localMs} only AFTER the async HTTP response arrived, so the "skew" it measured was
     * really skew-plus-full-round-trip-latency -- a 120ms RTT would show up as 120ms of apparent
     * clock drift, fluctuating with network jitter rather than tracking true NTP drift. Fixed by
     * capturing {@code localStartMs} immediately before the call and estimating the true skew as
     * the midpoint: {@code (localStartMs + rtt/2) - serverMs}, which assumes the request and
     * response legs took roughly equal time -- a standard NTP-style approximation, good enough for
     * this bot's purpose (comparing against a multi-second {@code recvWindow} tolerance, not
     * disciplining a clock). A sample whose RTT is implausibly large is discarded outright rather
     * than folded into the estimate, so one slow/jittery request cannot swing the gate. */
    private static final long MAX_PLAUSIBLE_SKEW_SAMPLE_RTT_MS = 2_000L;

    private void scheduleWarmUpAndClockSkew() {
        Runnable warmUpAndSkew = () -> {
            restClient.warmUp().whenComplete((v, err) -> {
                if (err != null) {
                    LOG.debugf("warm-up ping failed: %s", err.toString());
                }
            });
            long localStartMs = System.currentTimeMillis();
            restClient.serverTime().whenComplete((serverMs, err) -> {
                if (err != null) {
                    LOG.debugf("server-time sample failed: %s", err.toString());
                    return;
                }
                long localEndMs = System.currentTimeMillis();
                long rtt = localEndMs - localStartMs;
                if (rtt < 0 || rtt > MAX_PLAUSIBLE_SKEW_SAMPLE_RTT_MS) {
                    LOG.debugf("discarding clock-skew sample: implausible RTT %dms", rtt);
                    return;
                }
                long skewMs = (localStartMs + rtt / 2) - serverMs;
                clockSkewNanos = skewMs * 1_000_000L;
                riskGates.updateClockSkew(clockSkewNanos);
            });
        };
        warmUpAndSkew.run(); // sample once at startup, before the feed connects
        vertx.setPeriodic(WARMUP_PERIOD_MS, id -> warmUpAndSkew.run());
    }

    /** cf-arb-bot-review-plan.md Tier 2 step 2.4: periodic feed-level staleness watchdog.
     * {@code RiskGates} already gates individual fire decisions on a stale book (per-triangle, on
     * the detector's hot path, at the tight {@code max-book-age-ms} threshold) -- ONE illiquid
     * symbol going quiet for a stretch is normal and already handled there. This watchdog instead
     * catches the whole CONNECTION going silently dead (a half-open socket, a stalled proxy) in a
     * way that never fires {@code closeHandler}: if EVERY symbol that has ever ticked has gone
     * quiet for {@link #CONNECTION_DEAD_THRESHOLD_NANOS}, something is wrong at the connection
     * level, not just one thin pair. */
    private void scheduleFeedWatchdog() {
        vertx.setPeriodic(WATCHDOG_PERIOD_MS, id -> {
            if (killSwitch.tripped()) {
                return;
            }
            long nowNanos = System.nanoTime();
            long freshestWarmedBookAgeNanos = Long.MAX_VALUE;
            boolean anyWarmed = false;
            for (int i = 0; i < books.symbolCount(); i++) {
                L2Book book = books.book(i);
                if (book.updateCount() == 0) {
                    continue; // never ticked yet -- not yet warm, not "stale" (L2Book's own javadoc)
                }
                anyWarmed = true;
                freshestWarmedBookAgeNanos = Math.min(freshestWarmedBookAgeNanos, book.ageNanos(nowNanos));
            }
            boolean feedDead = anyWarmed && freshestWarmedBookAgeNanos > CONNECTION_DEAD_THRESHOLD_NANOS;

            WatchdogDecision decision = decideWatchdogAction(anyWarmed, feedDead,
                    consecutiveFeedDeadDetections, FEED_DEAD_TRIP_THRESHOLD);
            consecutiveFeedDeadDetections = decision.nextConsecutiveFeedDeadDetections;
            if (decision.action != WatchdogAction.NONE) {
                LOG.warnf("feed watchdog: %s (detection %d/%d) -- forcing a reconnect",
                        feedDead ? "connection reports alive but no data across every warmed book for >"
                                + (CONNECTION_DEAD_THRESHOLD_NANOS / 1_000_000) + "ms"
                                : "still no data across any book after a forced reconnect",
                        decision.nextConsecutiveFeedDeadDetections, FEED_DEAD_TRIP_THRESHOLD);
                journal.write(JournalEvents.feedReconnect(decision.reason, decision.nextConsecutiveFeedDeadDetections));
                wsClient.forceReconnect(); // no-op if a reconnect is already in flight -- see its own javadoc
                if (decision.action == WatchdogAction.RECONNECT_AND_TRIP) {
                    killSwitch.recordFeedUnhealthy("sustained-stale-book-after-"
                            + decision.nextConsecutiveFeedDeadDetections + "-forced-reconnects");
                }
            }

            // Churn (5 reconnects inside a 5-minute window -- MexcWsClient.CHURN_THRESHOLD/
            // CHURN_WINDOW_MS) already IS "reconnection failing repeatedly": trip immediately, same
            // as before.
            if (wsClient.isChurning()) {
                killSwitch.recordFeedUnhealthy("connection-churning");
            }
        });
    }

    enum WatchdogAction { NONE, RECONNECT, RECONNECT_AND_TRIP }

    record WatchdogDecision(WatchdogAction action, int nextConsecutiveFeedDeadDetections, String reason) {
    }

    /**
     * Third-pass review finding (M3): pure decision function for the feed watchdog's escalation
     * counter, extracted out of the Vert.x timer callback so this state machine can be unit tested
     * without a live Vert.x/WebSocket harness (package-visible for {@code BotServiceWatchdogTest}).
     *
     * <p>The previous inline form reset {@code consecutiveFeedDeadDetections} to 0 whenever
     * {@code !feedDead} -- which is ALSO true immediately after {@code forceReconnect()} runs
     * (its {@code closeHandler} calls {@code books.resetAll()}, zeroing every book's
     * {@code updateCount}, which makes {@code anyWarmed} false on the very next tick regardless of
     * whether the reconnect actually restored data). A feed that goes fully dark after a forced
     * reconnect -- exactly the failure this escalation exists to catch -- would then see
     * {@code anyWarmed} stay false forever, silently resetting the counter to 0 on every subsequent
     * tick: the kill switch could never trip, and the bot would sit un-trading with no operator
     * signal beyond a readiness-check flip (MIN-05: no alarm wired to that either).
     *
     * <p>Only CONFIRMED health (a book with fresh, non-stale data -- {@code anyWarmed && !feedDead})
     * legitimately clears an escalation in progress. {@code !anyWarmed} while ALREADY mid-escalation
     * ({@code consecutiveFeedDeadDetectionsBefore > 0}) keeps counting instead, on the working
     * assumption that the last forced reconnect has not yet proven itself. A genuinely brand-new
     * process (never warmed, never escalated) still does nothing, matching {@code L2Book}'s own "not
     * yet warm, not stale" semantics.
     */
    static WatchdogDecision decideWatchdogAction(boolean anyWarmed, boolean feedDead,
                                                  int consecutiveFeedDeadDetectionsBefore, int tripThreshold) {
        boolean confirmedHealthy = anyWarmed && !feedDead;
        if (confirmedHealthy) {
            return new WatchdogDecision(WatchdogAction.NONE, 0, null);
        }
        boolean stillUnhealthy = feedDead || (consecutiveFeedDeadDetectionsBefore > 0 && !anyWarmed);
        if (!stillUnhealthy) {
            // Never warmed yet at all, and not already mid-escalation -- legitimately nothing to do.
            return new WatchdogDecision(WatchdogAction.NONE, consecutiveFeedDeadDetectionsBefore, null);
        }
        int next = consecutiveFeedDeadDetectionsBefore + 1;
        String reason = feedDead ? "sustained-stale-book" : "post-reconnect-still-dark";
        WatchdogAction action = next >= tripThreshold ? WatchdogAction.RECONNECT_AND_TRIP : WatchdogAction.RECONNECT;
        return new WatchdogDecision(action, next, reason);
    }

    private void scheduleLatencySnapshots() {
        vertx.setPeriodic(LATENCY_SNAPSHOT_PERIOD_MS, id -> {
            writeLatencySnapshot("frame_to_decision", metrics.frameToDecisionHistogram());
            writeLatencySnapshot("decision_to_leg1_ack", metrics.decisionToLeg1AckHistogram());
            writeLatencySnapshot("full_cycle", metrics.fullCycleHistogram());
        });
    }

    private void writeLatencySnapshot(String stage, org.HdrHistogram.ConcurrentHistogram h) {
        if (h.getTotalCount() == 0) {
            return;
        }
        journal.write(JournalEvents.latencySnapshot(
                h.getValueAtPercentile(50), h.getValueAtPercentile(99), h.getMaxValue(), stage));
    }

    private void logStartupSafetyBanner() {
        // Security rule S4: "live mode must require an explicit env override AND log a prominent
        // startup warning. Code must never auto-enable live." dryRun's default is baked into
        // BotConfig (@WithDefault("true")) and is never touched in code -- this banner is the
        // loud, hard-to-miss warning the rule requires whenever that default has been overridden.
        if (config.dryRun()) {
            LOG.info("=================================================================");
            LOG.info(" cf-arb-bot starting in DRY-RUN (paper trading) mode.");
            LOG.info(" No real orders will be placed. This is the safe default (S4).");
            LOG.info("=================================================================");
        } else {
            LOG.warn("=================================================================");
            LOG.warn(" *** cf-arb-bot starting in LIVE TRADING mode ***");
            LOG.warn(" CF_BOT_DRY_RUN=false was set explicitly -- REAL ORDERS WILL BE PLACED");
            LOG.warn(" with REAL MONEY on MEXC. Equity floor (kill switch): $"
                    + config.risk().equityFloorUsd());
            LOG.warn("=================================================================");
        }
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("live mode requires environment variable " + name
                    + " (security rule S1: never in config files) -- see cf-arb-bot-plan.md §6.2");
        }
        return v;
    }

    /** cf-arb-bot-review-plan.md Tier 2 step 2.2: an optional filesystem override
     * ({@code cf-bot.filters-path}) takes precedence; otherwise load the bundled classpath resource
     * so the service can start regardless of working directory (the previous CWD-relative
     * {@code config/mexc_filters.json} path meant the deployed service could never find this file --
     * Terraform never provisioned it, and systemd's WorkingDirectory would not have contained it). */
    private Map<String, SymbolFilter> loadFilters() {
        try {
            if (config.filtersPath().isPresent()) {
                return SymbolFilterLoader.load(Path.of(config.filtersPath().get()));
            }
            String resource = "/config/mexc_filters.json";
            try (InputStream in = BotService.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IOException("classpath resource " + resource + " not found");
                }
                return SymbolFilterLoader.load(in, "classpath:" + resource);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot load mexc_filters.json -- run the Gate 0 "
                    + "exchangeInfo fetch first (cf-arb-bot-plan.md §3 step 2/3), or set "
                    + "cf-bot.filters-path to a freshly re-fetched snapshot", e);
        }
    }

    // Accessors for BotApiResource/ReadinessCheck (read-only).
    public Portfolio portfolio() { return portfolio; }
    public KillSwitch killSwitch() { return killSwitch; }
    public TriangleRegistry triangleRegistry() { return triangles; }
    public BookRegistry bookRegistry() { return books; }
    public boolean wsClientConnected() { return wsClient != null && wsClient.isConnected(); }
    public boolean wsClientChurning() { return wsClient != null && wsClient.isChurning(); }
    public long clockSkewNanos() { return clockSkewNanos; }
    public long clockSkewToleranceNanos() { return clockSkewToleranceNanos; }
}

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
     * when literally every configured symbol has gone quiet at once. */
    private static final long CONNECTION_DEAD_THRESHOLD_NANOS = 5_000_000_000L;
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

    void onStart(@Observes StartupEvent ev) {
        logStartupSafetyBanner();

        Map<String, SymbolFilter> filters = loadFilters();
        this.books = new BookRegistry(config.symbols(), WARMUP_UPDATES, WARMUP_SECONDS);
        this.triangles = new TriangleRegistry(config, books, filters);
        LOG.infof("loaded %d triangles across %d symbols", triangles.triangleCount(), books.symbolCount());

        boolean dryRun = config.dryRun();
        if (!dryRun) {
            validateOrderTypeSupported(filters);
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
                triangles.triangleCount(), killSwitch);
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
                config.capital().compound());

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
            unwinder = new Unwinder(restClient, config.exec().orderType(), config.exec().legTimeoutMs());

            WebClientOptions userDataOpts = new WebClientOptions().setSsl(true).setDefaultHost(
                    URI.create(config.venue().restUrl()).getHost()).setDefaultPort(443);
            this.userDataStream = new UserDataStream(vertx, WebClient.create(vertx, userDataOpts), apiKey,
                    config.venue().wsUrl());
            userDataStream.start();
        }

        this.executor = new CycleExecutor(orderQueue, triangles, riskGates, killSwitch, portfolio,
                metrics, journal, dryRun, restClient, unwinder, config.exec().orderType(), config.exec().legTimeoutMs());
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

    /** cf-arb-bot-review-plan.md Tier 1 step 1.3 / new defect 3: fail closed in live mode if the
     * configured order type is not one the venue actually advertises for every configured symbol.
     * As of this writing NONE of the 9 configured symbols advertise IMMEDIATE_OR_CANCEL support --
     * see BotConfig.ExecConfig#orderType's javadoc -- so this will refuse to start in live mode
     * until that changes. That is the correct, safe outcome of an unresolved wire-format question,
     * not a bug in this check. */
    private void validateOrderTypeSupported(Map<String, SymbolFilter> filters) {
        String orderType = config.exec().orderType();
        for (Map.Entry<String, BotConfig.TriangleConfig> e : config.triangles().entrySet()) {
            if (!e.getValue().enabled()) {
                continue;
            }
            for (String leg : e.getValue().legs()) {
                String symbol = leg.split(":")[0];
                SymbolFilter filter = filters.get(symbol);
                if (filter != null && !filter.orderTypes().contains(orderType)) {
                    throw new IllegalStateException("cf-bot.exec.order-type=" + orderType + " is not supported "
                            + "by MEXC for symbol '" + symbol + "' (venue advertises " + filter.orderTypes()
                            + ") -- refusing to start in live mode (security rule S5); see "
                            + "BotConfig.ExecConfig#orderType's javadoc");
                }
            }
        }
    }

    private void scheduleWarmUpAndClockSkew() {
        Runnable warmUpAndSkew = () -> {
            restClient.warmUp().whenComplete((v, err) -> {
                if (err != null) {
                    LOG.debugf("warm-up ping failed: %s", err.toString());
                }
            });
            restClient.serverTime().whenComplete((serverMs, err) -> {
                if (err != null) {
                    LOG.debugf("server-time sample failed: %s", err.toString());
                    return;
                }
                long localMs = System.currentTimeMillis();
                clockSkewNanos = (localMs - serverMs) * 1_000_000L;
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
            if ((feedDead && wsClient.isConnected()) || wsClient.isChurning()) {
                killSwitch.recordFeedUnhealthy(feedDead ? "sustained-stale-book" : "connection-churning");
            }
        });
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

package io.cfarb.api;

import io.cfarb.BotService;
import io.cfarb.book.BookRegistry;
import io.cfarb.book.L2Book;
import io.cfarb.config.BotConfig;
import io.cfarb.graph.Triangle;
import io.cfarb.graph.TriangleRegistry;
import io.cfarb.metrics.BotMetrics;
import io.cfarb.risk.KillSwitch;
import io.cfarb.state.Portfolio;
import io.cfarb.util.FixedPoint;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only JSON API (security rule S12: "REST API stays read-only; no endpoint may mutate trading
 * state"). Serves cf-arb-bot-plan.md §8's metrics-aggregator requirement — operator halt is
 * {@code systemctl stop} over SSM, never a control-plane endpoint here.
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class BotApiResource {

    @Inject
    BotService botService;
    @Inject
    BotConfig config;
    @Inject
    BotMetrics metrics;

    @GET
    @Path("/state")
    public Map<String, Object> state() {
        Portfolio portfolio = botService.portfolio();
        KillSwitch killSwitch = botService.killSwitch();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dryRun", config.dryRun());
        m.put("equityUsd", FixedPoint.toDouble(portfolio.equity()));
        m.put("seedUsd", FixedPoint.toDouble(portfolio.seed()));
        m.put("pnlPctOfSeed", portfolio.pnlPctOfSeed());
        m.put("realizedTradeCount", portfolio.realizedTradeCount());
        m.put("brokenCycleCount", portfolio.brokenCycleCount());
        m.put("killSwitchTripped", killSwitch.tripped());
        m.put("killSwitchTripReason", killSwitch.tripReason());
        m.put("wsConnected", botService.wsClientConnected());
        m.put("wsChurning", botService.wsClientChurning());
        m.put("clockSkewMs", botService.clockSkewNanos() / 1_000_000.0);
        return m;
    }

    @GET
    @Path("/triangles")
    public Map<String, Object> triangles() {
        TriangleRegistry registry = botService.triangleRegistry();
        BookRegistry books = botService.bookRegistry();
        long nowNanos = System.nanoTime();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < registry.triangleCount(); i++) {
            Triangle t = registry.triangle(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("legs", legsSummary(t));
            boolean allFresh = true;
            for (int symbolIndex : t.symbolIndex()) {
                L2Book book = books.book(symbolIndex);
                if (!book.isTrusted() || book.isEmpty() || book.isCrossed()) {
                    allFresh = false;
                    break;
                }
            }
            row.put("booksReady", allFresh);
            out.put(t.name(), row);
        }
        return out;
    }

    /**
     * JOURNAL-TUNING-TASK.md T1a: per-symbol L2 book state — plan §8's unimplemented "per-symbol
     * book age" field, plus the crossed/trusted/empty predicates and top-of-book so an operator can
     * see WHICH predicate is false for a triangle whose {@code booksReady} is false. A negative
     * {@code spreadBps} is the {@code BTCUSDT} crossed-latch (§5.5) directly visible. Read-only, on
     * the Quarkus HTTP worker thread — the same advisory cross-thread read {@code /triangles}
     * already performs (see {@link L2Book}'s javadoc); no new hand-off.
     */
    @GET
    @Path("/books")
    public Map<String, Object> booksState() {
        BookRegistry books = botService.bookRegistry();
        long nowNanos = System.nanoTime();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < books.symbolCount(); i++) {
            L2Book book = books.book(i);
            long topBid = book.bestBidPx();
            long topAsk = book.bestAskPx();
            boolean haveTop = topBid != Long.MIN_VALUE && topAsk != Long.MIN_VALUE;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("trusted", book.isTrusted());
            row.put("crossed", book.isCrossed());
            row.put("empty", book.isEmpty());
            row.put("ageMs", book.ageNanos(nowNanos) / 1_000_000.0);
            row.put("updateCount", book.updateCount());
            row.put("bidCount", book.bidLevelCount());
            row.put("askCount", book.askLevelCount());
            row.put("topBid", haveTop ? FixedPoint.toDouble(topBid) : null);
            row.put("topAsk", haveTop ? FixedPoint.toDouble(topAsk) : null);
            row.put("spreadBps", haveTop ? spreadBps(topBid, topAsk) : null);
            row.put("crossedForMs", book.crossedForNanos(nowNanos) / 1_000_000.0);
            row.put("selfHealResets", book.crossedResetCount());
            out.put(books.symbol(i), row);
        }
        return out;
    }

    private static double spreadBps(long topBidFixed, long topAskFixed) {
        double bid = FixedPoint.toDouble(topBidFixed);
        double ask = FixedPoint.toDouble(topAskFixed);
        double mid = (bid + ask) / 2.0;
        return mid == 0 ? 0.0 : (ask - bid) / mid * 10_000.0;
    }

    @GET
    @Path("/config")
    public Map<String, Object> effectiveConfig() {
        // Secrets are never in BotConfig at all (cf-arb-bot-plan.md §6.2) -- nothing to redact.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dryRun", config.dryRun());
        m.put("symbols", config.symbols());
        m.put("strategyMinNetBps", config.strategy().minNetBps());
        m.put("strategySlippageBufferBps", config.strategy().slippageBufferBps());
        m.put("capitalSeedUsd", config.capital().seedUsd());
        m.put("riskEquityFloorUsd", config.risk().equityFloorUsd());
        m.put("riskMaxNotionalUsd", config.risk().maxNotionalUsd());
        m.put("venueDepthChannel", config.venue().depthChannel());
        return m;
    }

    @GET
    @Path("/latency")
    public Map<String, Object> latency() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frameToDecisionUs", histSummary(metrics.frameToDecisionHistogram()));
        m.put("decisionToLeg1AckUs", histSummary(metrics.decisionToLeg1AckHistogram()));
        m.put("fullCycleUs", histSummary(metrics.fullCycleHistogram()));
        return m;
    }

    private static Map<String, Object> histSummary(org.HdrHistogram.ConcurrentHistogram h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("p50", h.getValueAtPercentile(50) / 1000.0);
        m.put("p99", h.getValueAtPercentile(99) / 1000.0);
        m.put("max", h.getMaxValue() / 1000.0);
        m.put("count", h.getTotalCount());
        return m;
    }

    private static String legsSummary(Triangle t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(" -> ");
            sb.append(t.filter()[i].symbol()).append(':').append(t.side()[i]);
        }
        return sb.toString();
    }
}

package io.cfarb.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cfarb.book.BookRegistry;
import io.cfarb.book.L2Book;
import io.cfarb.config.BotConfig;
import io.cfarb.feed.MexcDepthDecoder;
import io.cfarb.graph.Triangle;
import io.cfarb.graph.TriangleRegistry;
import io.cfarb.journal.EventJournal;
import io.cfarb.metrics.BotMetrics;
import io.cfarb.model.OrderIntent;
import io.cfarb.model.Side;
import io.cfarb.model.SymbolFilter;
import io.cfarb.risk.KillSwitch;
import io.cfarb.risk.RiskGates;
import io.cfarb.state.Portfolio;
import io.cfarb.util.FixedPoint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jctools.queues.SpscArrayQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JOURNAL-TUNING-TASK.md T2: the sampled reject stream must journal the BEST candidate per window,
 * not the first, and carry {@code sampled_from}. JOURNAL-BPS-ANALYSIS.md §9.1: the old first-sample
 * form discarded 97 % of evaluations as a uniform sample, making every peak invisible.
 */
class OpportunityDetectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long WINDOW_MS = 1_000;
    private static final long NS = 1_000_000L;

    @TempDir
    Path tempDir;

    private static void seedBook(L2Book book, double bidPx, double bidQty, double askPx, double askQty, long nowNanos) {
        MexcDepthDecoder.DepthFrame f = new MexcDepthDecoder.DepthFrame();
        f.bidCount = 1;
        f.bidPx[0] = FixedPoint.fromDouble(bidPx);
        f.bidQty[0] = FixedPoint.fromDouble(bidQty);
        f.askCount = 1;
        f.askPx[0] = FixedPoint.fromDouble(askPx);
        f.askQty[0] = FixedPoint.fromDouble(askQty);
        f.fromVersion = -1;
        f.toVersion = -1;
        f.sendTimeMs = 0;
        book.apply(f, nowNanos);
    }

    private static SymbolFilter filter(String symbol, String base, String quote, double takerBps) {
        long feeMul = FixedPoint.fromDouble(1.0 - takerBps / 10_000.0);
        return new SymbolFilter(symbol, base, quote, 1L, 8, 0L, 0L, 8, takerBps, feeMul,
                java.util.Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.05, 0.05);
    }

    private static BotConfig.RiskConfig risk() {
        return new BotConfig.RiskConfig() {
            public double equityFloorUsd() { return 1.0; }
            public double maxNotionalUsd() { return 1_000.0; }
            public int maxOpenCycles() { return 1; }
            public int maxCyclesPerMinute() { return 1_000; }
            public long cycleCooldownMs() { return 0; }
            public int maxConsecutiveFailures() { return 3; }
        };
    }

    private static BotConfig.StrategyConfig strategy() {
        return new BotConfig.StrategyConfig() {
            public double minNetBps() { return 5.0; }
            public double slippageBufferBps() { return 1.0; }
            public long maxBookAgeMs() { return 3_600_000; } // never stale within a test
        };
    }

    private static BotConfig.ExecConfig exec() {
        return new BotConfig.ExecConfig() {
            public String orderType() { return "IOC"; }
            public long recvWindowMs() { return 5_000; }
            public long legTimeoutMs() { return 1_500; }
            public long unwindCrossBps() { return 40; }
            public long maxIntentAgeMs() { return 150; }
        };
    }

    @Test
    void journalsTheBestRejectInAWindowNotTheFirstAndCountsEveryTick() throws Exception {
        List<String> symbols = List.of("USDCUSDT", "XRPUSDC", "XRPUSDT");
        BookRegistry books = new BookRegistry(symbols, 1, 0);
        Map<String, SymbolFilter> filters = Map.of(
                "USDCUSDT", filter("USDCUSDT", "USDC", "USDT", 0.0),
                "XRPUSDC", filter("XRPUSDC", "XRP", "USDC", 0.0),
                "XRPUSDT", filter("XRPUSDT", "XRP", "USDT", 0.0));
        Map<String, BotConfig.TriangleConfig> triangleConfigs = Map.of("usdt-usdc-xrp-fwd",
                new BotConfig.TriangleConfig() {
                    public boolean enabled() { return true; }
                    public List<String> legs() { return List.of("USDCUSDT:ASK", "XRPUSDC:ASK", "XRPUSDT:BID"); }
                    public java.util.OptionalDouble maxNotionalUsd() { return java.util.OptionalDouble.empty(); }
                });
        TriangleRegistry triangles = new TriangleRegistry(triangleConfigs, "USDT", books, filters);
        Triangle tri = triangles.triangle(0);

        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(1_000.0));
        KillSwitch ks = new KillSwitch(portfolio, FixedPoint.fromDouble(1.0), 3);
        RiskGates gates = new RiskGates(risk(), strategy(), exec(), triangles.triangleCount(), ks, true);
        BotMetrics metrics = new BotMetrics(new SimpleMeterRegistry());
        EventJournal journal = new EventJournal(tempDir, metrics, false);
        journal.start();
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);

        // minNetBps deliberately huge -> every candidate takes the below-threshold reject path,
        // never fires (which would claim a slot and change state).
        OpportunityDetector detector = new OpportunityDetector(books, triangles, gates, portfolio,
                metrics, journal, queue, 1_000.0, 0.0, true, WINDOW_MS);

        int xrpusdt = books.indexOf("XRPUSDT");
        EdgeCalculator ref = new EdgeCalculator();
        EdgeCalculator.Result refOut = new EdgeCalculator.Result();
        long notional = FixedPoint.fromDouble(1_000.0);

        long t0 = 10_000_000_000L;
        double bestNet = Double.NEGATIVE_INFINITY;
        int ticks = 6;
        // Six ticks 120ms apart -> all inside one 1000ms window. The XRPUSDT bid rises each tick, so
        // net_bps rises; the MAX is the last tick, NOT the first.
        for (int i = 0; i < ticks; i++) {
            long now = t0 + i * 120 * NS;
            seedBook(books.book(0), 0.9999, 5_000_000, 1.0001, 5_000_000, now);   // USDCUSDT
            seedBook(books.book(1), 1.3799, 5_000_000, 1.3801, 5_000_000, now);   // XRPUSDC
            seedBook(books.book(2), 1.3790 + i * 0.0002, 5_000_000, 1.3999, 5_000_000, now); // XRPUSDT bid rises
            ref.evaluate(tri, books, notional, refOut);
            assertTrue(refOut.fillable, "tick " + i + " must be fillable");
            bestNet = Math.max(bestNet, refOut.netBps);
            detector.onBookUpdated(xrpusdt, now);
        }

        // Close the window: one more candidate well past 1000ms -> emits window 1's summary and
        // becomes the seed of window 2 (itself unemitted).
        long closeNow = t0 + 1_500 * NS;
        seedBook(books.book(0), 0.9999, 5_000_000, 1.0001, 5_000_000, closeNow);
        seedBook(books.book(1), 1.3799, 5_000_000, 1.3801, 5_000_000, closeNow);
        seedBook(books.book(2), 1.3790, 5_000_000, 1.3999, 5_000_000, closeNow);
        detector.onBookUpdated(xrpusdt, closeNow);

        List<JsonNode> opps = awaitOpportunities(1);
        journal.stop();
        assertEquals(1, opps.size(), "exactly one summary line for the closed window: " + opps);
        JsonNode line = opps.get(0);
        assertEquals(ticks, line.get("sampled_from").asInt(), "sampled_from must count every tick in the window");
        assertEquals(bestNet, line.get("net_bps").asDouble(), 1e-6,
                "emitted net_bps must be the window MAX, not the first tick's");
        assertEquals("below-threshold", line.get("reject_reason").asText());
        assertTrue(line.has("leg_top_px") && line.get("leg_top_px").size() == 3, "T4 per-leg depth present");
    }

    private List<JsonNode> awaitOpportunities(int expected) throws IOException, InterruptedException {
        for (int i = 0; i < 200; i++) {
            List<JsonNode> opps = readOpportunities();
            if (opps.size() >= expected) {
                return opps;
            }
            Thread.sleep(10);
        }
        return readOpportunities();
    }

    private List<JsonNode> readOpportunities() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".ndjson")).sorted()
                    .flatMap(p -> {
                        try {
                            return Files.readAllLines(p).stream();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .map(l -> {
                        try {
                            return MAPPER.readTree(l);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .filter(n -> "opportunity".equals(n.get("type").asText()))
                    .toList();
        }
    }
}

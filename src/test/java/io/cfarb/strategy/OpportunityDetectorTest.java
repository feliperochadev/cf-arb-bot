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

    // --- DUPLICATE-FIRE-TASK.md ("Fix A"): suppress a re-fire while the consumed liquidity is
    //     unchanged (same per-leg worst price + base qty + book write stamp) --------------------

    private static final List<String> FIRING_SYMBOLS = List.of("USDCUSDT", "XRPUSDC", "XRPUSDT");

    private record Rig(BookRegistry books, OpportunityDetector detector, EventJournal journal,
                       SimpleMeterRegistry registry) {
    }

    private static BotConfig.TriangleConfig triangleConfig(List<String> legs) {
        return new BotConfig.TriangleConfig() {
            public boolean enabled() { return true; }
            public List<String> legs() { return legs; }
            public java.util.OptionalDouble maxNotionalUsd() { return java.util.OptionalDouble.empty(); }
        };
    }

    private static BotConfig.RiskConfig firingRisk() {
        return new BotConfig.RiskConfig() {
            public double equityFloorUsd() { return 1.0; }
            public double maxNotionalUsd() { return 1_000.0; }
            public int maxOpenCycles() { return 8; }        // several fires can be "open" at once
            public int maxCyclesPerMinute() { return 10_000; }
            public long cycleCooldownMs() { return 0; }       // time cooldown must not mask the behaviour under test
            public int maxConsecutiveFailures() { return 3; }
        };
    }

    /** Profitable ~77 bps forward triangle: 1000 USDT -> USDC @1.0 -> XRP @1.30 -> USDT @1.31. */
    private static void seedProfitable(BookRegistry books, long now) {
        seedBook(books.book(0), 0.9999, 5_000_000, 1.0000, 5_000_000, now);  // USDCUSDT
        seedBook(books.book(1), 1.2999, 5_000_000, 1.3000, 5_000_000, now);  // XRPUSDC
        seedBook(books.book(2), 1.3100, 5_000_000, 1.3200, 5_000_000, now);  // XRPUSDT
    }

    private Rig firingRig(SpscArrayQueue<OrderIntent> queue) throws IOException {
        BookRegistry books = new BookRegistry(FIRING_SYMBOLS, 1, 0);
        Map<String, SymbolFilter> filters = Map.of(
                "USDCUSDT", filter("USDCUSDT", "USDC", "USDT", 0.0),
                "XRPUSDC", filter("XRPUSDC", "XRP", "USDC", 0.0),
                "XRPUSDT", filter("XRPUSDT", "XRP", "USDT", 0.0));
        Map<String, BotConfig.TriangleConfig> triangleConfigs = Map.of(
                "usdt-usdc-xrp-fwd", triangleConfig(List.of("USDCUSDT:ASK", "XRPUSDC:ASK", "XRPUSDT:BID")));
        TriangleRegistry triangles = new TriangleRegistry(triangleConfigs, "USDT", books, filters);
        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(1_000.0));
        KillSwitch ks = new KillSwitch(portfolio, FixedPoint.fromDouble(1.0), 3);
        RiskGates gates = new RiskGates(firingRisk(), strategy(), exec(), triangles.triangleCount(), ks, true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BotMetrics metrics = new BotMetrics(registry);
        metrics.initRuntimeCounters(triangles.triangleNames(), FIRING_SYMBOLS);
        EventJournal journal = new EventJournal(tempDir, metrics, false);
        journal.start();
        // reject-sample interval 0 -> rejects journal immediately, so a duplicate-signature reject
        // is observable without a window-flush tick.
        OpportunityDetector detector = new OpportunityDetector(books, triangles, gates, portfolio,
                metrics, journal, queue, 5.0, 1.0, true, 0L);
        return new Rig(books, detector, journal, registry);
    }

    private static int drain(SpscArrayQueue<OrderIntent> q) {
        int c = 0;
        while (q.poll() != null) {
            c++;
        }
        return c;
    }

    private static long countDuplicateRejects(List<JsonNode> opps) {
        return opps.stream()
                .filter(n -> n.has("reject_reason")
                        && "duplicate-signature".equals(n.get("reject_reason").asText()))
                .count();
    }

    @Test
    void firesOnceThenSuppressesTheRepeatWhileTheBookIsUnchanged() throws Exception {
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
        Rig rig = firingRig(queue);
        int xrpusdt = rig.books().indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        seedProfitable(rig.books(), t0);

        rig.detector().onBookUpdated(xrpusdt, t0);
        // Same book, not re-applied -> identical signature -> the repeat must be suppressed.
        rig.detector().onBookUpdated(xrpusdt, t0 + NS);

        List<JsonNode> opps = awaitOpportunities(2);
        rig.journal().stop();

        assertEquals(1, drain(queue), "exactly one OrderIntent enqueued -- the repeat is suppressed");
        assertEquals(1, opps.stream().filter(n -> n.get("fired").asBoolean()).count(), "one fire line");
        assertEquals(1, countDuplicateRejects(opps), "the repeat journalled a duplicate-signature reject");
        assertEquals(1.0, rig.registry().get("cfarb.detector.duplicate_fire").counter().count(), 1e-9);
    }

    @Test
    void reFiresWhenTheConsumedLevelIsRewrittenEvenAtTheSamePriceAndSize() throws Exception {
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
        Rig rig = firingRig(queue);
        int xrpusdt = rig.books().indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        seedProfitable(rig.books(), t0);
        rig.detector().onBookUpdated(xrpusdt, t0);

        // Re-apply the SAME prices and sizes: apply() rewrites every touched level in place and
        // bumps its write stamp, so the signature differs on writeSeq ALONE. This is the test that
        // proves the fix is more than price+qty equality.
        seedProfitable(rig.books(), t0 + 5 * NS);
        rig.detector().onBookUpdated(xrpusdt, t0 + 5 * NS);

        awaitOpportunities(2);
        rig.journal().stop();
        assertEquals(2, drain(queue), "a rewritten level re-arms the opportunity");
    }

    @Test
    void reFiresWhenPriceOrSizeGenuinelyChanges() throws Exception {
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
        Rig rig = firingRig(queue);
        int xrpusdt = rig.books().indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        seedProfitable(rig.books(), t0);
        rig.detector().onBookUpdated(xrpusdt, t0);

        // XRPUSDT bid moves up -> different leg-2 worst price -> new signature.
        seedBook(rig.books().book(2), 1.3125, 5_000_000, 1.3200, 5_000_000, t0 + 5 * NS);
        rig.detector().onBookUpdated(xrpusdt, t0 + 5 * NS);

        awaitOpportunities(2);
        rig.journal().stop();
        assertEquals(2, drain(queue), "a genuine price move re-arms the opportunity");
    }

    @Test
    void aFailedOfferDoesNotStoreTheSignatureSoTheNextIdenticalCandidateStillFires() throws Exception {
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(2);
        Rig rig = firingRig(queue);
        int xrpusdt = rig.books().indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        seedProfitable(rig.books(), t0);

        // Fill the queue so the detector's offer() fails on the first attempt.
        OrderIntent filler = new OrderIntent(0L, 0, 0L, 0.0, new long[3], new long[3]);
        while (queue.offer(filler)) {
            // fill to capacity
        }

        rig.detector().onBookUpdated(xrpusdt, t0);       // offer fails, claim rolled back, no signature stored
        while (queue.poll() != null) {
            // drain the fillers
        }
        rig.detector().onBookUpdated(xrpusdt, t0 + NS);   // identical book -> must still be allowed to fire

        awaitOpportunities(1);
        rig.journal().stop();
        assertEquals(1, drain(queue), "the fire after a failed offer must go through -- signature was not stored");
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

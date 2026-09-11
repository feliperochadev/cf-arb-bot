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

    /** Genuinely moves the ask top by DELETING the old level and inserting the new one in the same
     * frame, matching MEXC's real differential-update semantics -- unlike calling {@link #seedBook}
     * again with a different askPx (which INSERTS a second level, leaving the old, still-better
     * price as the real top and silently defeating any test that means to change it). */
    private static void replaceAsk(L2Book book, double oldAskPx, double newAskPx, double qty, long nowNanos) {
        MexcDepthDecoder.DepthFrame f = new MexcDepthDecoder.DepthFrame();
        f.fromVersion = -1;
        f.toVersion = -1;
        f.sendTimeMs = 0;
        f.askCount = 2;
        f.askPx[0] = FixedPoint.fromDouble(oldAskPx);
        f.askQty[0] = 0; // delete
        f.askPx[1] = FixedPoint.fromDouble(newAskPx);
        f.askQty[1] = FixedPoint.fromDouble(qty);
        book.apply(f, nowNanos);
    }

    private static SymbolFilter filter(String symbol, String base, String quote, double takerBps) {
        long feeMul = FixedPoint.fromDouble(1.0 - takerBps / 10_000.0);
        return new SymbolFilter(symbol, base, quote, 1L, 8, 0L, 0L, 8, takerBps, feeMul,
                java.util.Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.05, 0.05);
    }

    private static SymbolFilter filterWithQtyStep(String symbol, String base, String quote, double takerBps,
                                                   double qtyStep) {
        long feeMul = FixedPoint.fromDouble(1.0 - takerBps / 10_000.0);
        return new SymbolFilter(symbol, base, quote, FixedPoint.fromDouble(qtyStep), 8, 0L, 0L, 8, takerBps,
                feeMul, java.util.Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.05, 0.05);
    }

    private static BotConfig.RiskConfig risk() {
        return new BotConfig.RiskConfig() {
            public double equityFloorUsd() { return 1.0; }
            public double maxNotionalUsd() { return 1_000.0; }
            public int maxOpenCycles() { return 1; }
            public int maxCyclesPerMinute() { return 1_000; }
            public long cycleCooldownMs() { return 0; }
            public int maxConsecutiveFailures() { return 3; }
            public double maxNotionalPerWindowUsd() { return 1_000_000.0; }
            public long notionalWindowMs() { return 60_000; }
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

    private static Map<String, SymbolFilter> quantizationDragFilters() {
        // DYNAMIC-SIZING-TASK.md Phase 1: a coarse 1-whole-XRP qty step on XRPUSDC gives this
        // fixture real quantization drag (gross_bps ends up several bps above net_bps). Without it
        // gross==net exactly (no fee, single deep level per side), and no minNetBps threshold could
        // sit strictly between "clears the gross ceiling" and "below the fire threshold" at once --
        // the gross early-out shares that one threshold value between both checks.
        return Map.of(
                "USDCUSDT", filter("USDCUSDT", "USDC", "USDT", 0.0),
                "XRPUSDC", filterWithQtyStep("XRPUSDC", "XRP", "USDC", 0.0, 1.0),
                "XRPUSDT", filter("XRPUSDT", "XRP", "USDT", 0.0));
    }

    private static Map<String, BotConfig.TriangleConfig> forwardTriangleConfig() {
        return Map.of("usdt-usdc-xrp-fwd", new BotConfig.TriangleConfig() {
            public boolean enabled() { return true; }
            public List<String> legs() { return List.of("USDCUSDT:ASK", "XRPUSDC:ASK", "XRPUSDT:BID"); }
            public java.util.OptionalDouble maxNotionalUsd() { return java.util.OptionalDouble.empty(); }
        });
    }

    private static void seedDragTick(BookRegistry books, int i, long now) {
        seedBook(books.book(0), 0.9999, 5_000_000, 1.0001, 5_000_000, now);   // USDCUSDT
        seedBook(books.book(1), 1.3799, 5_000_000, 1.3801, 5_000_000, now);   // XRPUSDC
        // A small per-tick step keeps the gross_bps SPREAD across ticks well inside the ~7bps
        // constant quantization drag this fixture's coarse XRPUSDC qty step produces -- otherwise
        // the worst tick's gross_bps and the best tick's net_bps could cross, leaving no threshold
        // able to separate "clears the gross ceiling" from "below the fire threshold" for every tick
        // at once (see quantizationDragFilters()'s note).
        seedBook(books.book(2), 1.3790 + i * 0.00002, 5_000_000, 1.3999, 5_000_000, now); // XRPUSDT bid rises
    }

    @Test
    void journalsTheBestRejectInAWindowNotTheFirstAndCountsEveryTick() throws Exception {
        List<String> symbols = List.of("USDCUSDT", "XRPUSDC", "XRPUSDT");
        Map<String, SymbolFilter> filters = quantizationDragFilters();
        Map<String, BotConfig.TriangleConfig> triangleConfigs = forwardTriangleConfig();
        long notional = FixedPoint.fromDouble(1_000.0);
        long t0 = 10_000_000_000L;
        int ticks = 6;

        // Pre-scan (a throwaway BookRegistry/EdgeCalculator, never touching the detector under
        // test) to derive a threshold that sits strictly between every tick's gross_bps (so none of
        // them gross-bail) and every tick's net_bps (so all of them stay below-threshold, never
        // fire) -- see quantizationDragFilters()'s note on why that requires real drag.
        double minGross = Double.POSITIVE_INFINITY;
        double maxNet = Double.NEGATIVE_INFINITY;
        {
            BookRegistry scanBooks = new BookRegistry(symbols, 1, 0);
            TriangleRegistry scanTriangles = new TriangleRegistry(triangleConfigs, "USDT", scanBooks, filters);
            Triangle scanTri = scanTriangles.triangle(0);
            EdgeCalculator ref = new EdgeCalculator();
            EdgeCalculator.Result refOut = new EdgeCalculator.Result();
            for (int i = 0; i < ticks; i++) {
                seedDragTick(scanBooks, i, t0 + i * 120 * NS);
                ref.evaluate(scanTri, scanBooks, notional, refOut);
                assertTrue(refOut.fillable, "tick " + i + " must be fillable");
                minGross = Math.min(minGross, refOut.grossBps);
                maxNet = Math.max(maxNet, refOut.netBps);
            }
        }
        assertTrue(minGross > maxNet, "fixture must have enough quantization drag for a threshold to "
                + "separate \"clears the gross ceiling\" from \"below the fire threshold\": minGross="
                + minGross + " maxNet=" + maxNet);
        double threshold = (minGross + maxNet) / 2.0;

        BookRegistry books = new BookRegistry(symbols, 1, 0);
        TriangleRegistry triangles = new TriangleRegistry(triangleConfigs, "USDT", books, filters);
        Triangle tri = triangles.triangle(0);

        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(1_000.0));
        KillSwitch ks = new KillSwitch(portfolio, FixedPoint.fromDouble(1.0), 3);
        RiskGates gates = new RiskGates(risk(), strategy(), exec(), triangles.triangleCount(), ks, true);
        BotMetrics metrics = new BotMetrics(new SimpleMeterRegistry());
        EventJournal journal = new EventJournal(tempDir, metrics, false);
        journal.start();
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);

        // threshold sits strictly between every tick's gross_bps and net_bps -- never gross-bails,
        // never fires (which would claim a slot and change state), always below-threshold.
        OpportunityDetector detector = new OpportunityDetector(books, triangles, gates, portfolio,
                metrics, journal, queue, threshold, 0.0, true, WINDOW_MS);

        int xrpusdt = books.indexOf("XRPUSDT");
        EdgeCalculator ref = new EdgeCalculator();
        EdgeCalculator.Result refOut = new EdgeCalculator.Result();
        double bestNet = Double.NEGATIVE_INFINITY;
        // Six ticks 120ms apart -> all inside one 1000ms window. The XRPUSDT bid rises each tick, so
        // net_bps rises; the MAX is the last tick, NOT the first.
        for (int i = 0; i < ticks; i++) {
            long now = t0 + i * 120 * NS;
            seedDragTick(books, i, now);
            ref.evaluate(tri, books, notional, refOut);
            assertTrue(refOut.fillable, "tick " + i + " must be fillable");
            bestNet = Math.max(bestNet, refOut.netBps);
            detector.onBookUpdated(xrpusdt, now);
        }

        // Close the window: one more candidate well past 1000ms -> emits window 1's summary and
        // becomes the seed of window 2 (itself unemitted).
        long closeNow = t0 + 1_500 * NS;
        seedDragTick(books, 0, closeNow);
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

    @Test
    void aCandidateBelowTheGrossCeilingJournalsBelowGrossCeilingNotUnfillable() throws Exception {
        // A huge minNotional on the XRPUSDT leg makes a full evaluation UNFILLABLE regardless of
        // price -- this is what lets a LATER, non-diagnostic (gross-bailed) candidate with a higher
        // grossBps win the window-best ranking over the mandatory first-tick diagnostic evaluation
        // (both are "non-fillable"; within that tier the ranking compares grossBps -- see
        // isBetterThanPending's javadoc), so the window's final emitted line is the bailed one, not
        // the unfillable one.
        List<String> symbols = List.of("USDCUSDT", "XRPUSDC", "XRPUSDT");
        long hugeMinNotional = FixedPoint.fromDouble(1_000_000_000.0);
        Map<String, SymbolFilter> filters = Map.of(
                "USDCUSDT", filter("USDCUSDT", "USDC", "USDT", 0.0),
                "XRPUSDC", filter("XRPUSDC", "XRP", "USDC", 0.0),
                "XRPUSDT", new SymbolFilter("XRPUSDT", "XRP", "USDT", 1L, 8, 0L, hugeMinNotional, 8, 0.0,
                        FixedPoint.fromDouble(1.0), java.util.Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.05, 0.05));
        Map<String, BotConfig.TriangleConfig> triangleConfigs = forwardTriangleConfig();
        BookRegistry books = new BookRegistry(symbols, 1, 0);
        TriangleRegistry triangles = new TriangleRegistry(triangleConfigs, "USDT", books, filters);

        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(1_000.0));
        KillSwitch ks = new KillSwitch(portfolio, FixedPoint.fromDouble(1.0), 3);
        RiskGates gates = new RiskGates(risk(), strategy(), exec(), triangles.triangleCount(), ks, true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BotMetrics metrics = new BotMetrics(registry);
        EventJournal journal = new EventJournal(tempDir, metrics, false);
        journal.start();
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);

        // minNetBps deliberately huge -- every non-diagnostic tick's grossBps sits far below the
        // fire gate, so it bails before the (doomed-anyway) ladder walk ever runs.
        OpportunityDetector detector = new OpportunityDetector(books, triangles, gates, portfolio,
                metrics, journal, queue, 1_000.0, 0.0, true, WINDOW_MS);

        int xrpusdt = books.indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        // Tick 1 (mandatory first-tick diagnostic, bail=-infinity): fully evaluates, genuinely
        // UNFILLABLE (minNotional). Opens the window as its pending.
        seedBook(books.book(0), 0.9999, 5_000_000, 1.0001, 5_000_000, t0);
        seedBook(books.book(1), 1.3799, 5_000_000, 1.3801, 5_000_000, t0);
        seedBook(books.book(2), 1.3790, 5_000_000, 1.3999, 5_000_000, t0);
        detector.onBookUpdated(xrpusdt, t0);
        // Tick 2 (same window, non-diagnostic): a HIGHER bid -> higher grossBps, but still nowhere
        // near the 1000bps gate -> bails. Higher grossBps than tick 1's beats it in the non-fillable
        // tier's ranking, becoming the new window pending.
        long t1 = t0 + 100 * NS;
        seedBook(books.book(0), 0.9999, 5_000_000, 1.0001, 5_000_000, t1);
        seedBook(books.book(1), 1.3799, 5_000_000, 1.3801, 5_000_000, t1);
        seedBook(books.book(2), 1.3990, 5_000_000, 1.3999, 5_000_000, t1);
        detector.onBookUpdated(xrpusdt, t1);
        // Close the window.
        long tClose = t0 + 1_500 * NS;
        seedBook(books.book(0), 0.9999, 5_000_000, 1.0001, 5_000_000, tClose);
        seedBook(books.book(1), 1.3799, 5_000_000, 1.3801, 5_000_000, tClose);
        seedBook(books.book(2), 1.3790, 5_000_000, 1.3999, 5_000_000, tClose);
        detector.onBookUpdated(xrpusdt, tClose);

        List<JsonNode> opps = awaitOpportunities(1);
        journal.stop();
        assertEquals(1, opps.size(), "one summary line for the closed window: " + opps);
        JsonNode line = opps.get(0);
        assertEquals("below-gross-ceiling", line.get("reject_reason").asText(),
                "the higher-grossBps bailed candidate must win over the unfillable diagnostic one");
        assertEquals(true, line.get("net_bps").isNull(), "a bailed candidate never ran the ladder walk");
        assertEquals(false, line.get("gross_bps").isNull(), "gross_bps is still populated");
        // TWO, not three: tick 1 (the mandatory first-ever diagnostic) AND tClose (the tick that
        // closes window 1 is ALWAYS itself diagnostic too -- windowStartNanos and lastFullEvalNanos
        // reset in lockstep at the start of every window, so both timers always expire together)
        // each genuinely ran the ladder walk and failed. Tick 2 (gross-bailed) never reaches that
        // code path at all, so it must NOT add a third -- bailed and unfillable are counted
        // separately even though both journal as "non-fillable".
        assertEquals(2.0, registry.get("cfarb.opportunities.rejected_unfillable").counter().count(), 1e-9,
                "only the genuinely-unfillable ticks count here -- a bailed candidate must not share it");
    }

    @Test
    void theDiagnosticSurvivesAcrossTwoSampleWindowsEvenThoughMostTicksBail() throws Exception {
        List<String> symbols = List.of("USDCUSDT", "XRPUSDC", "XRPUSDT");
        Map<String, SymbolFilter> filters = quantizationDragFilters();
        Map<String, BotConfig.TriangleConfig> triangleConfigs = forwardTriangleConfig();
        BookRegistry books = new BookRegistry(symbols, 1, 0);
        TriangleRegistry triangles = new TriangleRegistry(triangleConfigs, "USDT", books, filters);

        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(1_000.0));
        KillSwitch ks = new KillSwitch(portfolio, FixedPoint.fromDouble(1.0), 3);
        RiskGates gates = new RiskGates(risk(), strategy(), exec(), triangles.triangleCount(), ks, true);
        BotMetrics metrics = new BotMetrics(new SimpleMeterRegistry());
        EventJournal journal = new EventJournal(tempDir, metrics, false);
        journal.start();
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);

        // minNetBps huge -> almost every tick gross-bails, EXCEPT the one forced full evaluation
        // per triangle per WINDOW_MS window -- that one must still carry a real net_bps alongside
        // gross_bps, keeping JOURNAL-BPS-ANALYSIS.md's paired drag diagnostic alive.
        OpportunityDetector detector = new OpportunityDetector(books, triangles, gates, portfolio,
                metrics, journal, queue, 1_000.0, 0.0, true, WINDOW_MS);
        int xrpusdt = books.indexOf("XRPUSDT");

        long t0 = 10_000_000_000L;
        // Window 1: several sub-gate ticks, all inside WINDOW_MS.
        for (int i = 0; i < 4; i++) {
            seedDragTick(books, 0, t0 + i * 100 * NS);
            detector.onBookUpdated(xrpusdt, t0 + i * 100 * NS);
        }
        // Close window 1 and drive several more sub-gate ticks in window 2.
        long t1 = t0 + (WINDOW_MS + 200) * NS;
        for (int i = 0; i < 4; i++) {
            seedDragTick(books, 0, t1 + i * 100 * NS);
            detector.onBookUpdated(xrpusdt, t1 + i * 100 * NS);
        }
        // Close window 2.
        long t2 = t1 + (WINDOW_MS + 200) * NS;
        seedDragTick(books, 0, t2);
        detector.onBookUpdated(xrpusdt, t2);

        List<JsonNode> opps = awaitOpportunities(2);
        journal.stop();
        assertEquals(2, opps.size(), "one summary line per closed window: " + opps);
        for (JsonNode line : opps) {
            assertEquals(false, line.get("net_bps").isNull(),
                    "each window's forced diagnostic evaluation must carry a real net_bps: " + line);
            assertEquals(false, line.get("gross_bps").isNull());
            assertEquals("below-threshold", line.get("reject_reason").asText(),
                    "the diagnostic tick ran the full ladder walk and is a real below-threshold reject");
        }
    }

    @Test
    void aCandidateAboveTheGrossCeilingIsAlwaysFullyEvaluatedRegardlessOfWindowState() throws Exception {
        // A huge minNotional makes every FULL evaluation unfillable, regardless of price -- a
        // side effect observable one-per-tick via metrics, unlike net_bps/reject_reason which the
        // window-best mechanism can hide behind an earlier winner. minNetBps is deliberately very
        // negative so grossBps (a small negative number on this fixture) ALWAYS clears the gate --
        // every one of many ticks, spread across several windows and several diagnostic intervals,
        // must independently reach the ladder walk and register as unfillable. If even one were
        // wrongly bailed, this count would fall short.
        List<String> symbols = List.of("USDCUSDT", "XRPUSDC", "XRPUSDT");
        long hugeMinNotional = FixedPoint.fromDouble(1_000_000_000.0);
        Map<String, SymbolFilter> filters = Map.of(
                "USDCUSDT", filter("USDCUSDT", "USDC", "USDT", 0.0),
                "XRPUSDC", filter("XRPUSDC", "XRP", "USDC", 0.0),
                "XRPUSDT", new SymbolFilter("XRPUSDT", "XRP", "USDT", 1L, 8, 0L, hugeMinNotional, 8, 0.0,
                        FixedPoint.fromDouble(1.0), java.util.Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.05, 0.05));
        Map<String, BotConfig.TriangleConfig> triangleConfigs = forwardTriangleConfig();
        BookRegistry books = new BookRegistry(symbols, 1, 0);
        TriangleRegistry triangles = new TriangleRegistry(triangleConfigs, "USDT", books, filters);

        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(1_000.0));
        KillSwitch ks = new KillSwitch(portfolio, FixedPoint.fromDouble(1.0), 3);
        RiskGates gates = new RiskGates(risk(), strategy(), exec(), triangles.triangleCount(), ks, true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BotMetrics metrics = new BotMetrics(registry);
        EventJournal journal = new EventJournal(tempDir, metrics, false);
        journal.start();
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);

        // minNetBps very negative -> grossBps always clears the gate, on every tick.
        long shortWindowMs = 50;
        OpportunityDetector detector = new OpportunityDetector(books, triangles, gates, portfolio,
                metrics, journal, queue, -1_000.0, 0.0, true, shortWindowMs);
        int xrpusdt = books.indexOf("XRPUSDT");

        long t0 = 10_000_000_000L;
        int ticks = 10;
        for (int i = 0; i < ticks; i++) {
            long now = t0 + i * 30 * NS; // 30ms apart -> spans several 50ms windows
            seedBook(books.book(0), 0.9999, 5_000_000, 1.0001, 5_000_000, now);
            seedBook(books.book(1), 1.3799, 5_000_000, 1.3801, 5_000_000, now);
            seedBook(books.book(2), 1.3790, 5_000_000, 1.3999, 5_000_000, now);
            detector.onBookUpdated(xrpusdt, now);
        }
        journal.stop();

        assertEquals((double) ticks, registry.get("cfarb.opportunities.rejected_unfillable").counter().count(),
                1e-9, "every tick's grossBps clears the gate, so every one of them must reach the "
                        + "(genuinely unfillable) ladder walk -- none may be silently gross-bailed");
    }

    @Test
    void orderIntentAndJournalLineCarryTheChosenNotionalNotTheCap() throws Exception {
        // Leg 0 (USDCUSDT) has a 2-level ask ladder: touch (price 0.990, qty 700 -> ~101bps) and a
        // deeper, worse level (price 0.997, qty 100,000 -> ~30bps). Legs 1/2 are continuous,
        // fee-free pass-throughs, so -- exactly like EdgeCalculatorTest's dollar-optimum fixture,
        // but sized so the TOUCH wins this time -- profit($693) > profit($2000): the cap must NOT
        // be what fires.
        List<String> symbols = List.of("USDCUSDT", "XRPUSDC", "XRPUSDT");
        Map<String, SymbolFilter> filters = Map.of(
                "USDCUSDT", filter("USDCUSDT", "USDC", "USDT", 0.0),
                "XRPUSDC", filter("XRPUSDC", "XRP", "USDC", 0.0),
                "XRPUSDT", filter("XRPUSDT", "XRP", "USDT", 0.0));
        Map<String, BotConfig.TriangleConfig> triangleConfigs = forwardTriangleConfig();
        BookRegistry books = new BookRegistry(symbols, 1, 0);
        TriangleRegistry triangles = new TriangleRegistry(triangleConfigs, "USDT", books, filters);

        // TriangleRegistry's test constructor leaves each triangle's own cap unbounded (only
        // RiskGates.canFire's maxNotionalUsd check enforces one here), so seed equity AT the $2000
        // cap -- candidateNotional = min(equity, triangle cap) = 2000, matching RiskGates' own gate.
        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(2_000.0));
        KillSwitch ks = new KillSwitch(portfolio, FixedPoint.fromDouble(1.0), 3);
        // The cap is $2000 -- if the fire used the cap verbatim (the pre-Phase-2 behaviour), the
        // OrderIntent/journal notional would be 2000, not the ~693 the search actually picks.
        BotConfig.RiskConfig risk = new BotConfig.RiskConfig() {
            public double equityFloorUsd() { return 1.0; }
            public double maxNotionalUsd() { return 2_000.0; }
            public int maxOpenCycles() { return 8; }
            public int maxCyclesPerMinute() { return 10_000; }
            public long cycleCooldownMs() { return 0; }
            public int maxConsecutiveFailures() { return 3; }
            public double maxNotionalPerWindowUsd() { return 1_000_000.0; }
            public long notionalWindowMs() { return 60_000; }
        };
        RiskGates gates = new RiskGates(risk, strategy(), exec(), triangles.triangleCount(), ks, true);
        BotMetrics metrics = new BotMetrics(new SimpleMeterRegistry());
        EventJournal journal = new EventJournal(tempDir, metrics, false);
        journal.start();
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
        OpportunityDetector detector = new OpportunityDetector(books, triangles, gates, portfolio,
                metrics, journal, queue, 5.0, 1.0, true, 0L);

        int xrpusdt = books.indexOf("XRPUSDT");
        long now = 10_000_000_000L;
        seedAsksTwoLevels(books.book(0), new double[]{0.990, 0.997}, new double[]{700.0, 100_000.0}, now);
        seedAsksTwoLevels(books.book(1), new double[]{1.0}, new double[]{1_000_000.0}, now);
        // XRPUSDT BID (selling XRP for USDT): make the bid ample and >1 so the whole triangle keeps
        // a real, comfortably-above-threshold edge at both candidate sizes.
        MexcDepthDecoder.DepthFrame f = new MexcDepthDecoder.DepthFrame();
        f.fromVersion = -1;
        f.toVersion = -1;
        f.sendTimeMs = 0;
        f.bidCount = 1;
        f.bidPx[0] = FixedPoint.fromDouble(1.0);
        f.bidQty[0] = FixedPoint.fromDouble(1_000_000.0);
        f.askCount = 1;
        f.askPx[0] = FixedPoint.fromDouble(1.000001);
        f.askQty[0] = FixedPoint.fromDouble(1.0);
        books.book(2).apply(f, now);

        detector.onBookUpdated(xrpusdt, now);

        List<JsonNode> opps = awaitOpportunities(1);
        journal.stop();

        OrderIntent intent = queue.poll();
        assertTrue(intent != null, "the candidate must clear threshold and fire");
        assertEquals(693.0, FixedPoint.toDouble(intent.candidateNotionalFixed()), 0.5,
                "OrderIntent must carry the search's chosen $693 touch size, not the $2000 cap");

        assertEquals(1, opps.size());
        JsonNode line = opps.get(0);
        assertEquals(true, line.get("fired").asBoolean());
        assertEquals(693.0, line.get("notional_usd").asDouble(), 0.5,
                "the journal's notional_usd must match the chosen size too, not the cap");
        assertEquals(2, line.get("size_candidates").asInt(), "the touch and the cap were both evaluated");
        assertEquals(30.09, line.get("net_bps_at_cap").asDouble(), 0.01,
                "net_bps_at_cap is what fixed-size sizing at the $2000 cap would have produced -- "
                        + "lower than the chosen size's own ~101bps net_bps, proving the A/B value is real");
    }

    private static void seedAsksTwoLevels(L2Book book, double[] askPx, double[] askQty, long now) {
        MexcDepthDecoder.DepthFrame f = new MexcDepthDecoder.DepthFrame();
        f.fromVersion = -1;
        f.toVersion = -1;
        f.sendTimeMs = 0;
        f.bidCount = 1;
        f.bidPx[0] = FixedPoint.fromDouble(askPx[0] - 0.001);
        f.bidQty[0] = FixedPoint.fromDouble(1_000.0);
        f.askCount = askPx.length;
        for (int i = 0; i < askPx.length; i++) {
            f.askPx[i] = FixedPoint.fromDouble(askPx[i]);
            f.askQty[i] = FixedPoint.fromDouble(askQty[i]);
        }
        book.apply(f, now);
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
            public double maxNotionalPerWindowUsd() { return 1_000_000.0; }
            public long notionalWindowMs() { return 60_000; }
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

    /** Same rig as {@link #firingRig}, but with an EXPLICIT stale-leg guard configuration (PRE-LIVE-PLAN.md
     * P0-2(d)) instead of the constructor's own defaults. */
    private Rig firingRigWithStaleLeg(SpscArrayQueue<OrderIntent> queue, long staleLegFrozenMs,
                                       long staleLegActiveMs) throws IOException {
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
        OpportunityDetector detector = new OpportunityDetector(books, triangles, gates, portfolio,
                metrics, journal, queue, 5.0, 1.0, true, 0L, 0L,
                0.10, 30_000L, staleLegFrozenMs, staleLegActiveMs);
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

    // --- PRE-LIVE-PLAN.md P0-2(b): duplicate suppression survives one churning leg -------------
    // Note: seedProfitable's books are deliberately 5,000,000 deep against a ~$1000 trade, so NO
    // leg is ever "material" (baseQty/touchQty << the 0.10 default) -- that is why every existing
    // Fix-A test above (built on seedProfitable/firingRig) keeps passing unchanged: the new
    // any-material-leg rule never independently fires on those fixtures, and behaviour reduces
    // exactly to Fix A's original all-three-legs-including-write-stamp rule. The tests below use
    // shallower, genuinely material depth on the frozen legs to exercise the new rule itself.

    @Test
    void materialFrozenLegsSuppressRepeatedFiresEvenAsAnotherLegMoves() throws Exception {
        // Mirrors the usdt-sol-btc-rev / usdt-btc-usdc-rev incidents (PRE-LIVE-PLAN.md Review §2):
        // legs 1-2 sit at a genuinely frozen price with modest (material) depth while leg 0 drifts
        // -- Fix A's original all-three-legs rule fails to suppress (leg 0's worst price differs
        // every time), but the new any-material-leg rule catches legs 1-2 being unchanged.
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
        Rig rig = firingRig(queue);
        int xrpusdt = rig.books().indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;

        seedBook(rig.books().book(1), 1.2999, 5_000, 1.3000, 5_000, t0);   // XRPUSDC -- frozen, material
        seedBook(rig.books().book(2), 1.3100, 5_000, 1.3200, 5_000, t0);   // XRPUSDT -- frozen, material
        seedBook(rig.books().book(0), 0.9999, 5_000_000, 1.0000, 5_000_000, t0); // USDCUSDT -- deep, moves
        rig.detector().onBookUpdated(xrpusdt, t0);

        long t1 = t0 + 5 * NS;
        replaceAsk(rig.books().book(0), 1.0000, 1.00002, 5_000_000, t1);
        rig.detector().onBookUpdated(xrpusdt, t1);
        long t2 = t0 + 10 * NS;
        replaceAsk(rig.books().book(0), 1.00002, 0.99998, 5_000_000, t2);
        rig.detector().onBookUpdated(xrpusdt, t2);

        List<JsonNode> opps = awaitOpportunities(3);
        rig.journal().stop();

        assertEquals(1, drain(queue), "legs 1-2 frozen and material -> only the first candidate fires");
        assertEquals(1, opps.stream().filter(n -> n.get("fired").asBoolean()).count());
        assertEquals(2, countDuplicateRejects(opps), "the next two candidates are suppressed as duplicates");
    }

    @Test
    void aLegThatIsNotMaterialCannotByItselfSuppressAFire() throws Exception {
        // The materiality gate's whole purpose: a deep, effectively-constant leg (this order barely
        // touches its depth) must not veto suppression on its own. seedProfitable's 5,000,000 depth
        // is deliberately too deep for the ~$1000 trade to ever be material on any leg -- if
        // materiality worked backwards (deep legs counting as "unchanging" and material), this
        // would wrongly suppress the second fire even though nothing here is genuinely frozen AND
        // material at once.
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
        Rig rig = firingRig(queue);
        int xrpusdt = rig.books().indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        seedProfitable(rig.books(), t0);
        rig.detector().onBookUpdated(xrpusdt, t0);

        long t1 = t0 + 5 * NS;
        replaceAsk(rig.books().book(0), 1.0000, 1.00002, 5_000_000, t1);
        rig.detector().onBookUpdated(xrpusdt, t1);

        awaitOpportunities(2);
        rig.journal().stop();
        assertEquals(2, drain(queue), "no leg is both frozen and material -- both candidates must fire");
    }

    @Test
    void aReplenishedTouchOnAFrozenMaterialLegDoesNotSuppress() throws Exception {
        // Negative control (PRE-LIVE-PLAN.md P0-2(b) verify list): same price, touch INCREASED ->
        // "nobody replenished it" no longer holds, so this must still fire twice even though the
        // price is identical and the leg is material.
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
        Rig rig = firingRig(queue);
        int xrpusdt = rig.books().indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        seedBook(rig.books().book(1), 1.2999, 5_000, 1.3000, 5_000, t0);
        seedBook(rig.books().book(2), 1.3100, 5_000, 1.3200, 5_000, t0);
        seedBook(rig.books().book(0), 0.9999, 5_000_000, 1.0000, 5_000_000, t0);
        rig.detector().onBookUpdated(xrpusdt, t0);

        // Same prices everywhere, but legs 1-2's touch quantity is REPLENISHED (increased).
        long t1 = t0 + 5 * NS;
        seedBook(rig.books().book(1), 1.2999, 20_000, 1.3000, 20_000, t1);
        seedBook(rig.books().book(2), 1.3100, 20_000, 1.3200, 20_000, t1);
        rig.detector().onBookUpdated(xrpusdt, t1);

        awaitOpportunities(2);
        rig.journal().stop();
        assertEquals(2, drain(queue), "a replenished (increased) touch must still fire, even at an identical price");
    }

    // --- PRE-LIVE-PLAN.md P0-2(c): post-reset quarantine --------------------------------------

    @Test
    void quarantinesATriangleWithARecentlyResetLegThenFiresOnceTheWindowElapses() throws Exception {
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
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
        long quarantineMs = 2_000;
        OpportunityDetector detector = new OpportunityDetector(books, triangles, gates, portfolio,
                metrics, journal, queue, 5.0, 1.0, true, 0L, quarantineMs);

        int xrpusdt = books.indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        // Simulate the XRPUSDT leg having just reset (self-heal / gap / reconnect) at t0, then
        // immediately re-warming with a profitable book -- isTrusted() is true right away
        // (warmupUpdates=1), but the leg is still within the quarantine window.
        books.book(xrpusdt).reset(t0);
        seedProfitable(books, t0);

        long t500 = t0 + 500 * NS;
        detector.onBookUpdated(xrpusdt, t500);
        assertEquals(0, drain(queue), "quarantined: must not fire 500ms after the leg reset");
        assertEquals(1.0, registry.get("cfarb.detector.post_reset_skip").tag("symbol", "XRPUSDT")
                .counter().count(), 1e-9);

        long t3000 = t0 + 3_000 * NS;
        detector.onBookUpdated(xrpusdt, t3000);
        assertEquals(1, drain(queue), "quarantine window elapsed: must fire normally");
        journal.stop();
    }

    @Test
    void quarantineDisabledAtZeroNeverSkips() throws Exception {
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
        Rig rig = firingRig(queue); // firingRig's 10-arg constructor call defaults quarantine to 0 (disabled)
        int xrpusdt = rig.books().indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        rig.books().book(xrpusdt).reset(t0);
        seedProfitable(rig.books(), t0);

        // Immediately after the reset -- would be quarantined if enabled, but it is not.
        rig.detector().onBookUpdated(xrpusdt, t0);

        awaitOpportunities(1);
        rig.journal().stop();
        assertEquals(1, drain(queue), "quarantine disabled (0) must never skip a fire");
    }

    @Test
    void materialFractionOutsideZeroToOneRefusesToBoot() throws Exception {
        // PRE-LIVE-PLAN.md P0-2(b): S6 -- a misconfigured materiality gate is loud and fatal.
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
        BotMetrics metrics = new BotMetrics(new SimpleMeterRegistry());
        EventJournal journal = new EventJournal(tempDir, metrics, false);
        journal.start();
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                new OpportunityDetector(books, triangles, gates, portfolio, metrics, journal, queue,
                        5.0, 1.0, true, 0L, 0L, 0.0, 30_000L));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                new OpportunityDetector(books, triangles, gates, portfolio, metrics, journal, queue,
                        5.0, 1.0, true, 0L, 0L, -0.5, 30_000L));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                new OpportunityDetector(books, triangles, gates, portfolio, metrics, journal, queue,
                        5.0, 1.0, true, 0L, 0L, 1.5, 30_000L));
        journal.stop();
    }

    // --- PRE-LIVE-PLAN.md P0-2(d): stale-leg guard ---------------------------------------------

    @Test
    void oneFrozenLegPlusOneActiveLegIsRefused() throws Exception {
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
        long frozenMs = 1_000;
        long activeMs = 200;
        Rig rig = firingRigWithStaleLeg(queue, frozenMs, activeMs);
        int xrpusdt = rig.books().indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        seedProfitable(rig.books(), t0); // all three legs' tops stamped at t0

        // Legs 0-1 (USDCUSDT, XRPUSDC) are never touched again -- by the time we evaluate they are
        // frozen. Leg 2 (XRPUSDT) gets a fresh top-level touch just before evaluating (same price,
        // different qty still counts -- see L2BookTest).
        long tTouch = t0 + (frozenMs + 50) * NS - (activeMs - 50) * NS;
        seedBook(rig.books().book(2), 1.3100, 6_000_000, 1.3200, 5_000_000, tTouch);
        long tEval = t0 + (frozenMs + 50) * NS;
        rig.detector().onBookUpdated(xrpusdt, tEval);

        assertEquals(0, drain(queue), "leg 2 active while legs 0-1 are frozen -- must be refused as lag");
        assertEquals(1.0, rig.registry().get("cfarb.detector.stale_leg").counter().count(), 1e-9);
    }

    @Test
    void allLegsActiveFiresNormally() throws Exception {
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
        Rig rig = firingRigWithStaleLeg(queue, 1_000, 200);
        int xrpusdt = rig.books().indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        seedProfitable(rig.books(), t0); // every leg's top is fresh (age 0 at evaluation time)
        rig.detector().onBookUpdated(xrpusdt, t0);

        awaitOpportunities(1);
        rig.journal().stop();
        assertEquals(1, drain(queue), "every leg active -- must fire normally");
    }

    @Test
    void allLegsFrozenFiresNormallyAQuietBookIsNotADefect() throws Exception {
        SpscArrayQueue<OrderIntent> queue = new SpscArrayQueue<>(16);
        long frozenMs = 1_000;
        long activeMs = 200;
        Rig rig = firingRigWithStaleLeg(queue, frozenMs, activeMs);
        int xrpusdt = rig.books().indexOf("XRPUSDT");
        long t0 = 10_000_000_000L;
        seedProfitable(rig.books(), t0);

        // Evaluate long after every leg's last (and only) top change -- ALL three are frozen, NONE
        // is active. The guard requires both conditions, so this must fire, not be refused.
        long tEval = t0 + (frozenMs + 5_000) * NS;
        rig.detector().onBookUpdated(xrpusdt, tEval);

        awaitOpportunities(1);
        rig.journal().stop();
        assertEquals(1, drain(queue), "a uniformly quiet book (all legs frozen) is not a defect");
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

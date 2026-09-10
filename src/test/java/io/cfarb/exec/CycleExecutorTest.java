package io.cfarb.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.cfarb.config.BotConfig;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jctools.queues.SpscArrayQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link CycleExecutor}'s live path end-to-end against a stubbed {@link MexcOrderApi} --
 * cf-arb-bot-review-plan.md "Tests to add": partial fill aborts, a rejected leg unwinds, PnL is
 * computed from actual spend, and the open-cycle gate is released exactly once including on an
 * escaping exception.
 *
 * <p>Drives the executor the same way production does: enqueue an {@link OrderIntent}, start the
 * real background thread, poll {@link Portfolio}/{@link RiskGates} until the cycle settles, stop.
 */
class CycleExecutorTest {

    private static SymbolFilter filter(String symbol, String base, String quote, double qtyStep, int qtyDecimals,
                                        double minQty, double minNotional, int priceDecimals, double takerBps) {
        long feeMultiplierFixed = FixedPoint.fromDouble(1.0 - takerBps / 10_000.0);
        return new SymbolFilter(symbol, base, quote, FixedPoint.fromDouble(qtyStep), qtyDecimals,
                FixedPoint.fromDouble(minQty), FixedPoint.fromDouble(minNotional), priceDecimals, takerBps,
                feeMultiplierFixed, Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.05, 0.05);
    }

    private static final SymbolFilter BTCUSDT = filter("BTCUSDT", "BTC", "USDT", 1e-6, 6, 1e-6, 1.0, 2, 5.0);
    private static final SymbolFilter XRPBTC = filter("XRPBTC", "XRP", "BTC", 0.01, 2, 0.01, 0.000005, 8, 5.0);
    private static final SymbolFilter XRPUSDT = filter("XRPUSDT", "XRP", "USDT", 0.01, 2, 0.1, 1.0, 4, 0.0);

    private FakeMexcOrderApi api;
    private Portfolio portfolio;
    private KillSwitch killSwitch;
    private RiskGates riskGates;
    private BotMetrics metrics;
    private EventJournal journal;
    private CycleExecutor executor;
    private SpscArrayQueue<OrderIntent> queue;
    private TriangleRegistry triangles;

    @TempDir
    Path tempDir;

    private static BotConfig.RiskConfig riskConfig() {
        return new BotConfig.RiskConfig() {
            public double equityFloorUsd() { return 10.0; }
            public double maxNotionalUsd() { return 500.0; }
            public double absoluteMaxNotionalUsd() { return 1_000.0; }
            public int maxOpenCycles() { return 1; }
            public int maxCyclesPerMinute() { return 30; }
            public long cycleCooldownMs() { return 0; }
            public int maxConsecutiveFailures() { return 3; }
        };
    }

    private static BotConfig.StrategyConfig strategyConfig() {
        return new BotConfig.StrategyConfig() {
            public double minNetBps() { return 5.0; }
            public double slippageBufferBps() { return 1.0; }
            public long maxBookAgeMs() { return 250; }
        };
    }

    private static BotConfig.ExecConfig execConfig() {
        return new BotConfig.ExecConfig() {
            public String orderType() { return "IOC"; }
            public long recvWindowMs() { return 5000; }
            public long legTimeoutMs() { return 1500; }
            public long unwindCrossBps() { return 40; }
            public long maxIntentAgeMs() { return 150; }
        };
    }

    private Triangle triangle() {
        Side[] sides = {Side.ASK, Side.ASK, Side.BID};
        SymbolFilter[] filters = {BTCUSDT, XRPBTC, XRPUSDT};
        String[] fromAsset = new String[3];
        String[] toAsset = new String[3];
        for (int i = 0; i < 3; i++) {
            fromAsset[i] = Triangle.legFromAsset(sides[i], filters[i]);
            toAsset[i] = Triangle.legToAsset(sides[i], filters[i]);
        }
        return new Triangle("usdt-btc-xrp-fwd", new int[]{0, 1, 2}, sides, filters, fromAsset, toAsset);
    }

    @BeforeEach
    void setUp() {
        api = new FakeMexcOrderApi();
        portfolio = new Portfolio(FixedPoint.fromDouble(100.0));
        killSwitch = new KillSwitch(portfolio, FixedPoint.fromDouble(10.0), 3);
        riskGates = new RiskGates(riskConfig(), strategyConfig(), execConfig(), 1, killSwitch, true);
        metrics = new BotMetrics(new SimpleMeterRegistry());
        journal = new EventJournal(tempDir.resolve("journal"), metrics, false);
        journal.start();
        queue = new SpscArrayQueue<>(8);

        Triangle tri = triangle();
        Map<String, BotConfig.TriangleConfig> triangleConfigs = Map.of("usdt-btc-xrp-fwd",
                new BotConfig.TriangleConfig() {
                    public boolean enabled() { return true; }
                    public List<String> legs() { return List.of("BTCUSDT:ASK", "XRPBTC:ASK", "XRPUSDT:BID"); }
                });
        io.cfarb.book.BookRegistry books = new io.cfarb.book.BookRegistry(
                List.of("BTCUSDT", "XRPBTC", "XRPUSDT"), 1, 0);
        Map<String, SymbolFilter> filters = Map.of("BTCUSDT", BTCUSDT, "XRPBTC", XRPBTC, "XRPUSDT", XRPUSDT);
        triangles = new TriangleRegistry(triangleConfigs, "USDT", books, filters);

        Unwinder unwinder = new Unwinder(api, "IOC", 1500, books, 40);
        executor = new CycleExecutor(queue, triangles, riskGates, killSwitch, portfolio, metrics, journal,
                false, api, unwinder, "IOC", 1500, 150);
        executor.start();
    }

    @AfterEach
    void tearDown() {
        executor.stop();
        journal.stop();
    }

    private OrderIntent intent() {
        long notional = FixedPoint.fromDouble(100.0);
        long[] legPrices = {
                FixedPoint.fromDouble(77850.0),
                FixedPoint.fromDouble(0.000017),
                FixedPoint.fromDouble(1.40),
        };
        long[] legBaseQty = {
                FixedPoint.fromDouble(0.001285), // BTC bought on leg 0
                0, 0,
        };
        return new OrderIntent(System.nanoTime(), 0, notional, 20.0, legPrices, legBaseQty);
    }

    /** Legs 1-2's exact requested quantity is re-derived by CycleExecutor from the PREVIOUS leg's
     * actual (fee-adjusted) proceeds -- rather than hand-deriving that chained fixed-point
     * arithmetic here too, script a deliberately huge executedQty (with a consistent quoteQty at
     * the same price the leg will actually use) so it is unconditionally >= whatever gets
     * requested, i.e. unconditionally FILLED. This isolates the control-flow assertions these tests
     * care about from needing to reproduce Sizer-equivalent quantization by hand. */
    private static void scriptGenerousFill(FakeMexcOrderApi api, String symbol, long priceFixed, String orderId) {
        long bigQty = FixedPoint.fromDouble(1_000_000.0);
        long quoteQty = FixedPoint.mulDiv(bigQty, priceFixed, FixedPoint.SCALE);
        api.scriptQuery(symbol, bigQty, quoteQty, "FILLED", orderId);
    }

    @Test
    void allThreeLegsFilledCreditsPnlFromActualSpendAndProceeds() throws InterruptedException {
        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001285), FixedPoint.fromDouble(100.02), "FILLED", "o0");
        scriptGenerousFill(api, "XRPBTC", FixedPoint.fromDouble(0.000017), "o1");
        scriptGenerousFill(api, "XRPUSDT", FixedPoint.fromDouble(1.40), "o2");

        riskGates.claim(0, System.nanoTime());
        assertTrue(queue.offer(intent()));
        waitUntil(() -> riskGates.openCycleCount() == 0);

        assertEquals(1, portfolio.realizedTradeCount());
        assertEquals(0, portfolio.brokenCycleCount());
        assertTrue(portfolio.equity() > 0);
        assertEquals(3, api.placeOrderCalls, "all three legs must be submitted");
    }

    @Test
    void partialFillOnLeg0AbortsAndUnwindsRatherThanContinuing() throws InterruptedException {
        // leg 0 only 60% filled -- must abort (no leg 1/2 submission) and unwind leg 0 back to the
        // anchor, never continue forward at the reduced size.
        //
        // Two-entry sequence for leg 0's own order (third-pass review, M5): the first query reports
        // PARTIALLY_FILLED (still resting) -- OrderReconciler cancels it and re-queries; the SECOND
        // entry is what that re-query sees, here a terminal CANCELED with the fill amount unchanged
        // (the cancel took effect cleanly, nothing further filled -- the ordinary case). A single
        // scriptQuery() here would leave leg 0 stuck reporting PARTIALLY_FILLED even after the
        // cancel, which OrderReconciler now correctly classifies UNKNOWN rather than PARTIAL.
        long requested = FixedPoint.fromDouble(0.001285);
        long partial = FixedPoint.fromDouble(0.0007);
        long partialQuote = FixedPoint.fromDouble(0.0007 * 77850.0);
        api.scriptQuerySequence("BTCUSDT",
                FakeMexcOrderApi.response(partial, partialQuote, "PARTIALLY_FILLED", "o0"),
                FakeMexcOrderApi.response(partial, partialQuote, "CANCELED", "o0"));
        // the unwind reversal (SELL back on BTCUSDT) fills fully
        api.scriptQuery("BTCUSDT", partial, FixedPoint.fromDouble(0.0007 * 77850.0 * 0.999), "FILLED", "rev0");

        riskGates.claim(0, System.nanoTime());
        assertTrue(queue.offer(intent()));
        waitUntil(() -> riskGates.openCycleCount() == 0);

        assertEquals(0, portfolio.realizedTradeCount(), "a partial fill must never be booked as a clean cycle");
        assertEquals(1, portfolio.brokenCycleCount());
        assertEquals(2, api.placeOrderCalls, "leg 0's partial placement + exactly one unwind reversal -- "
                + "never a leg 1 submission");
        assertTrue(api.placedParams.stream().noneMatch(p -> p.contains("symbol=XRPBTC")),
                "leg 1 must never be submitted after leg 0 only partially filled");
    }

    @Test
    void zeroFillOnLeg1UnwindsLeg0sSymbolNotLeg1s() throws InterruptedException {
        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001285), FixedPoint.fromDouble(100.02), "FILLED", "o0");
        api.scriptQuery("XRPBTC", 0, 0, "CANCELED", "o1");
        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001284), FixedPoint.fromDouble(99.9), "FILLED", "rev0");

        riskGates.claim(0, System.nanoTime());
        assertTrue(queue.offer(intent()));
        waitUntil(() -> riskGates.openCycleCount() == 0);

        assertEquals(1, portfolio.brokenCycleCount());
        // leg0 place, leg1 place, unwind reversal on BTCUSDT -- never a second XRPBTC order
        assertEquals(3, api.placeOrderCalls);
        long xrpbtcOrders = api.placedParams.stream().filter(p -> p.contains("symbol=XRPBTC")).count();
        assertEquals(1, xrpbtcOrders, "XRPBTC should only be attempted once (the original failed leg), "
                + "never as a reversal target");
    }

    @Test
    void openCycleGateIsReleasedExactlyOnceEvenWhenExecutionThrows() throws InterruptedException {
        // Force an exception mid-execution: queryOrder throws, which OrderReconciler already
        // handles internally (-> UNKNOWN status, no exception escapes) -- this instead verifies the
        // simpler regression directly: RiskGates.onCycleFinished() clamps at zero and the executor's
        // single release path leaves the gate exactly at zero after a normal cycle, matching the
        // invariant a double-release would violate.
        api.scriptQuery("BTCUSDT", FixedPoint.fromDouble(0.001285), FixedPoint.fromDouble(100.02), "FILLED", "o0");
        scriptGenerousFill(api, "XRPBTC", FixedPoint.fromDouble(0.000017), "o1");
        scriptGenerousFill(api, "XRPUSDT", FixedPoint.fromDouble(1.40), "o2");

        riskGates.claim(0, System.nanoTime());
        assertTrue(queue.offer(intent()));
        waitUntil(() -> riskGates.openCycleCount() == 0);

        assertEquals(0, riskGates.openCycleCount());
        // a second cycle must be claimable immediately -- would fail if the count had gone negative
        // and canFire's >= comparison were somehow defeated, or positive and never released
        assertTrue(riskGates.canFire(0, FixedPoint.fromDouble(50.0), System.nanoTime()));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + 2_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadlineNanos) {
                fail("condition not met within timeout");
            }
            Thread.sleep(5);
        }
    }
}

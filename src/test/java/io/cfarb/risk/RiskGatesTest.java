package io.cfarb.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.config.BotConfig;
import io.cfarb.state.Portfolio;
import io.cfarb.util.FixedPoint;
import org.junit.jupiter.api.Test;

class RiskGatesTest {

    private static BotConfig.RiskConfig risk(double maxNotional, int maxOpen, int maxPerMin, long cooldownMs) {
        // Window budget generous enough to never bind existing tests that know nothing about it --
        // see the 6-arg overload below for tests that exercise P0-2(a) itself.
        return risk(maxNotional, maxOpen, maxPerMin, cooldownMs, 1_000_000.0, 60_000);
    }

    private static BotConfig.RiskConfig risk(double maxNotional, int maxOpen, int maxPerMin, long cooldownMs,
                                               double maxNotionalPerWindow, long notionalWindowMs) {
        return new BotConfig.RiskConfig() {
            public double equityFloorUsd() { return 50.0; }
            public double maxNotionalUsd() { return maxNotional; }
            public int maxOpenCycles() { return maxOpen; }
            public int maxCyclesPerMinute() { return maxPerMin; }
            public long cycleCooldownMs() { return cooldownMs; }
            public int maxConsecutiveFailures() { return 3; }
            public double maxNotionalPerWindowUsd() { return maxNotionalPerWindow; }
            public long notionalWindowMs() { return notionalWindowMs; }
        };
    }

    private static BotConfig.StrategyConfig strategy() {
        return new BotConfig.StrategyConfig() {
            public double minNetBps() { return 5.0; }
            public double slippageBufferBps() { return 1.0; }
            public long maxBookAgeMs() { return 250; }
        };
    }

    private static BotConfig.ExecConfig exec() {
        return new BotConfig.ExecConfig() {
            public String orderType() { return "IOC"; }
            public long recvWindowMs() { return 5000; }
            public long legTimeoutMs() { return 1500; }
            public long unwindCrossBps() { return 40; }
            public long maxIntentAgeMs() { return 150; }
        };
    }

    @Test
    void notionalAboveConfiguredCapIsRejected() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 1, 30, 250), strategy(), exec(), 1, ks, true);
        long ok = FixedPoint.fromDouble(150.0);
        long tooMuch = FixedPoint.fromDouble(250.0);
        assertTrue(gates.canFire(0, ok, 1_000_000L));
        assertFalse(gates.canFire(0, tooMuch, 1_000_000L));
    }

    @Test
    void largeButPositiveNotionalCapIsTrustedAsConfigured() {
        // max-notional-usd is the ONE notional cap -- no code ceiling clamps it any more. A big
        // deliberate value boots and is honoured verbatim; runtime min(equity, cap) + the kill
        // switch are what actually bound a live cycle.
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(50_000.0, 1, 30, 250), strategy(), exec(), 1, ks, true);
        assertEquals(50_000.0, gates.effectiveMaxNotionalUsd, 1e-9);
        assertTrue(gates.canFire(0, FixedPoint.fromDouble(40_000.0), 1_000_000L));
        assertFalse(gates.canFire(0, FixedPoint.fromDouble(60_000.0), 1_000_000L));
    }

    @Test
    void nonPositiveNotionalCapRefusesToBoot() {
        // S6: a misconfigured cap is loud and fatal -- RiskGates.failStartup() logs ERROR + throws.
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        assertThrows(IllegalStateException.class, () -> new RiskGates(
                risk(0.0, 1, 30, 250), strategy(), exec(), 1, ks, true));
        assertThrows(IllegalStateException.class, () -> new RiskGates(
                risk(-5.0, 1, 30, 250), strategy(), exec(), 1, ks, true));
    }

    @Test
    void perTriangleCooldownBlocksRapidRefire() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 5, 30, 250), strategy(), exec(), 2, ks, true);
        long amt = FixedPoint.fromDouble(50.0);
        long t0 = 10_000_000_000L;
        assertTrue(gates.canFire(0, amt, t0));
        gates.claim(0, amt, t0);
        assertFalse(gates.canFire(0, amt, t0 + 100_000_000L)); // 100ms later, cooldown is 250ms
        assertTrue(gates.canFire(0, amt, t0 + 300_000_000L));  // 300ms later, past cooldown
        // a DIFFERENT triangle is unaffected by triangle 0's cooldown
        assertTrue(gates.canFire(1, amt, t0 + 100_000_000L));
    }

    @Test
    void openCycleCapBlocksASecondConcurrentCycle() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 1, 30, 0), strategy(), exec(), 1, ks, true);
        long amt = FixedPoint.fromDouble(50.0);
        assertTrue(gates.canFire(0, amt, 1L));
        gates.claim(0, amt, 1L);
        assertFalse(gates.canFire(0, amt, 2L), "max-open-cycles=1, one is already open");
        gates.onCycleFinished();
        assertTrue(gates.canFire(0, amt, 3L), "the slot should free up once the cycle finishes");
    }

    @Test
    void cyclesPerMinuteCapBlocksAfterTheLimit() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 100, 2, 0), strategy(), exec(), 1, ks, true);
        long amt = FixedPoint.fromDouble(10.0);
        assertTrue(gates.canFire(0, amt, 0L));
        gates.claim(0, amt, 0L);
        gates.onCycleFinished();
        assertTrue(gates.canFire(0, amt, 1L));
        gates.claim(0, amt, 1L);
        gates.onCycleFinished();
        // third cycle within the same 60s window should be blocked (cap=2/min)
        assertFalse(gates.canFire(0, amt, 2L));
        // but after 60s has elapsed since the OLDEST of the two, it should free up
        assertTrue(gates.canFire(0, amt, 60_000_000_001L));
    }

    @Test
    void clockSkewToleranceIsHalfTheRecvWindowNotTheFullBoundary() {
        // Third-pass review finding (M7): the previous tolerance was the FULL recvWindow -- exactly
        // MEXC's own -1021 rejection boundary, leaving zero margin.
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 5, 30, 0), strategy(), exec(), 1, ks, false);
        assertEquals(2_500_000_000L, gates.clockSkewToleranceNanos(), "recvWindowMs=5000 -> 2500ms tolerance");
    }

    @Test
    void staleClockSkewSampleDegradesToUnknownAndFailsClosedInLiveMode() {
        // Third-pass review finding (M7): clockSkewKnown used to latch true FOREVER on the first
        // sample -- an hours-old sample kept passing canFire() indefinitely even if the host lost
        // its route to MEXC's time endpoint. A sample older than the staleness threshold must be
        // treated exactly like "never sampled": fail closed in LIVE mode (dryRun=false).
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 5, 30, 0), strategy(), exec(), 1, ks, false);
        long amt = FixedPoint.fromDouble(10.0);
        long t0 = 10_000_000_000L;

        assertFalse(gates.canFire(0, amt, t0), "no sample yet -- live mode fails closed");
        gates.updateClockSkew(0L); // a perfect (zero-skew) sample, taken "now" via System.nanoTime()

        assertTrue(gates.canFire(0, amt, t0), "a fresh sample must unblock live-mode firing");
        // Simulate the sample going stale: canFire's nowNanos is detector-thread System.nanoTime(),
        // the same clock updateClockSkew just recorded against -- push it far enough into the
        // future (well past CLOCK_SKEW_SAMPLE_MAX_AGE_NANOS) that the sample must be treated as
        // expired regardless of exactly when this test itself ran.
        long farFuture = System.nanoTime() + 300_000_000_000L; // +300s, past the 180s staleness window
        assertFalse(gates.canFire(0, amt, farFuture),
                "a stale clock-skew sample must degrade to 'unknown' and fail closed in live mode, "
                        + "not keep trusting an arbitrarily old value forever");
    }

    @Test
    void staleClockSkewSampleDoesNotBlockDryRun() {
        // The dry-run/live asymmetry (RiskGates' own documented rationale) must hold for a STALE
        // sample exactly as it does for a never-taken one.
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 5, 30, 0), strategy(), exec(), 1, ks, true);
        gates.updateClockSkew(0L);
        long farFuture = System.nanoTime() + 300_000_000_000L;
        assertTrue(gates.canFire(0, FixedPoint.fromDouble(10.0), farFuture),
                "dry-run never sends signed requests -- a stale/missing skew sample must not block it");
    }

    @Test
    void killSwitchTripBlocksEverything() {
        Portfolio p = new Portfolio(FixedPoint.fromDouble(100.0));
        KillSwitch ks = new KillSwitch(p, FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 5, 30, 0), strategy(), exec(), 1, ks, true);
        assertTrue(gates.canFire(0, FixedPoint.fromDouble(10.0), 1L));
        p.applyRealizedPnl(FixedPoint.fromDouble(-60.0)); // equity 100 -> 40, below the $50 floor
        ks.checkEquityFloor();
        assertTrue(ks.tripped());
        assertFalse(gates.canFire(0, FixedPoint.fromDouble(10.0), 2L));
    }

    // --- PRE-LIVE-PLAN.md P0-2(a): per-triangle notional budget per rolling window --------------

    @Test
    void windowBudgetBlocksTheNPlusOnethFireInsideTheWindow() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(10_000.0)), FixedPoint.fromDouble(50.0), 3);
        // max-notional-usd=1000 (per-order cap) is generous; the $2500 window budget is the binding
        // constraint here -- two $1000 fires fit ($2000 <= $2500), a third does not ($3000 > $2500).
        RiskGates gates = new RiskGates(risk(1_000.0, 100, 100, 0, 2_500.0, 60_000), strategy(), exec(), 1, ks, true);
        long amt = FixedPoint.fromDouble(1_000.0);
        long t0 = 10_000_000_000L;

        assertTrue(gates.canFire(0, amt, t0));
        gates.claim(0, amt, t0);
        gates.onCycleFinished();
        assertTrue(gates.canFire(0, amt, t0 + 1_000L), "$2000 spent so far, still under the $2500 budget");
        gates.claim(0, amt, t0 + 1_000L);
        gates.onCycleFinished();
        assertFalse(gates.canFire(0, amt, t0 + 2_000L), "a third $1000 fire would total $3000, over the $2500 budget");
    }

    @Test
    void windowBudgetRollsAfterTheConfiguredWindow() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(10_000.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(1_000.0, 100, 100, 0, 1_500.0, 60_000), strategy(), exec(), 1, ks, true);
        long amt = FixedPoint.fromDouble(1_000.0);
        long t0 = 10_000_000_000L;

        assertTrue(gates.canFire(0, amt, t0));
        gates.claim(0, amt, t0);
        gates.onCycleFinished();
        assertFalse(gates.canFire(0, amt, t0 + 1_000L), "$2000 would exceed the $1500 window budget");
        // 60s later the window has rolled -- the budget is fresh again.
        long t1 = t0 + 60_000_000_000L;
        assertTrue(gates.canFire(0, amt, t1), "the window rolled, so the budget is fresh again");
    }

    @Test
    void nonPositiveWindowBudgetConfigRefusesToBoot() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        assertThrows(IllegalStateException.class, () -> new RiskGates(
                risk(200.0, 1, 30, 250, 0.0, 60_000), strategy(), exec(), 1, ks, true));
        assertThrows(IllegalStateException.class, () -> new RiskGates(
                risk(200.0, 1, 30, 250, -5.0, 60_000), strategy(), exec(), 1, ks, true));
        assertThrows(IllegalStateException.class, () -> new RiskGates(
                risk(200.0, 1, 30, 250, 5_000.0, 0L), strategy(), exec(), 1, ks, true));
        assertThrows(IllegalStateException.class, () -> new RiskGates(
                risk(200.0, 1, 30, 250, 5_000.0, -1L), strategy(), exec(), 1, ks, true));
    }

    @Test
    void windowBudgetWouldBlockAttributesTheBlockForTelemetry() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(10_000.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(1_000.0, 100, 100, 0, 1_500.0, 60_000), strategy(), exec(), 1, ks, true);
        long amt = FixedPoint.fromDouble(1_000.0);
        long t0 = 10_000_000_000L;
        gates.claim(0, amt, t0);
        gates.onCycleFinished();

        assertFalse(gates.canFire(0, amt, t0 + 1_000L));
        assertTrue(gates.windowBudgetWouldBlock(0, amt, t0 + 1_000L),
                "the window budget is specifically what refuses this candidate");
    }
}

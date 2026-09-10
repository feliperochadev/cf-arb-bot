package io.cfarb.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.config.BotConfig;
import io.cfarb.state.Portfolio;
import io.cfarb.util.FixedPoint;
import org.junit.jupiter.api.Test;

class RiskGatesTest {

    /** 4-arg form: absolute-max-notional-usd defaults to the original frozen 1000.0, so the eight
     * tests that only care about the ordinary max-notional cap are unaffected by its introduction. */
    private static BotConfig.RiskConfig risk(double maxNotional, int maxOpen, int maxPerMin, long cooldownMs) {
        return risk(maxNotional, 1_000.0, maxOpen, maxPerMin, cooldownMs);
    }

    private static BotConfig.RiskConfig risk(double maxNotional, double absoluteMaxNotional,
                                              int maxOpen, int maxPerMin, long cooldownMs) {
        return new BotConfig.RiskConfig() {
            public double equityFloorUsd() { return 50.0; }
            public double maxNotionalUsd() { return maxNotional; }
            public double absoluteMaxNotionalUsd() { return absoluteMaxNotional; }
            public int maxOpenCycles() { return maxOpen; }
            public int maxCyclesPerMinute() { return maxPerMin; }
            public long cycleCooldownMs() { return cooldownMs; }
            public int maxConsecutiveFailures() { return 3; }
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
    void absoluteHardCapClampsEvenAMisconfiguredValue() {
        // S6: "a misconfiguration (e.g. notional=10^9) must be clamped and flagged at startup."
        // 4-arg risk() leaves absolute-max-notional-usd at the original frozen 1000.0.
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(1_000_000_000.0, 1, 30, 250), strategy(), exec(), 1, ks, true);
        assertTrue(gates.notionalWasClamped);
        assertEquals(1_000.0, gates.effectiveMaxNotionalUsd, 1e-9);
        long huge = FixedPoint.fromDouble(1500.0); // exceeds the 1000.0 absolute-max
        assertFalse(gates.canFire(0, huge, 1_000_000L));
    }

    @Test
    void absoluteMaxNotionalIsConfigurableButItselfCodeClamped() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);

        // A deliberately larger backstop lets a larger max-notional-usd through -- the whole point
        // of making the ceiling config instead of a frozen 1000.0 constant.
        RiskGates raised = new RiskGates(risk(50_000.0, 30_000.0, 1, 30, 250), strategy(), exec(), 1, ks, true);
        assertEquals(30_000.0, raised.effectiveMaxNotionalUsd, 1e-9);
        assertTrue(raised.notionalWasClamped);          // 50k clamped to the 30k backstop
        assertFalse(raised.absoluteMaxWasClamped);       // 30k is under the $1M code ceiling
        assertTrue(raised.canFire(0, FixedPoint.fromDouble(25_000.0), 1_000_000L));
        assertFalse(raised.canFire(0, FixedPoint.fromDouble(35_000.0), 1_000_000L));

        // S6 still holds for a typo in the BACKSTOP itself: 2e9 is clamped in code to $1M.
        RiskGates fatFingered = new RiskGates(risk(5_000.0, 2_000_000_000.0, 1, 30, 250), strategy(), exec(), 1, ks, true);
        assertTrue(fatFingered.absoluteMaxWasClamped);
        assertEquals(1_000_000.0, fatFingered.effectiveAbsoluteMaxNotionalUsd, 1e-9);
        assertEquals(5_000.0, fatFingered.effectiveMaxNotionalUsd, 1e-9); // real cap unaffected, still sane
    }

    @Test
    void nonPositiveAbsoluteMaxNotionalIsRejected() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new RiskGates(risk(200.0, 0.0, 1, 30, 250), strategy(), exec(), 1, ks, true));
    }

    @Test
    void perTriangleCooldownBlocksRapidRefire() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 5, 30, 250), strategy(), exec(), 2, ks, true);
        long amt = FixedPoint.fromDouble(50.0);
        long t0 = 10_000_000_000L;
        assertTrue(gates.canFire(0, amt, t0));
        gates.claim(0, t0);
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
        gates.claim(0, 1L);
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
        gates.claim(0, 0L);
        gates.onCycleFinished();
        assertTrue(gates.canFire(0, amt, 1L));
        gates.claim(0, 1L);
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
}

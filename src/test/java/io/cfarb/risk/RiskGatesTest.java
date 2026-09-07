package io.cfarb.risk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.config.BotConfig;
import io.cfarb.state.Portfolio;
import io.cfarb.util.FixedPoint;
import org.junit.jupiter.api.Test;

class RiskGatesTest {

    private static BotConfig.RiskConfig risk(double maxNotional, int maxOpen, int maxPerMin, long cooldownMs) {
        return new BotConfig.RiskConfig() {
            public double equityFloorUsd() { return 50.0; }
            public double maxNotionalUsd() { return maxNotional; }
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
        };
    }

    @Test
    void notionalAboveConfiguredCapIsRejected() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 1, 30, 250), strategy(), exec(), 1, ks);
        long ok = FixedPoint.fromDouble(150.0);
        long tooMuch = FixedPoint.fromDouble(250.0);
        assertTrue(gates.canFire(0, ok, 1_000_000L));
        assertFalse(gates.canFire(0, tooMuch, 1_000_000L));
    }

    @Test
    void absoluteHardCapClampsEvenAMisconfiguredValue() {
        // S6: "a misconfiguration (e.g. notional=10^9) must be clamped and flagged at startup."
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(1_000_000_000.0, 1, 30, 250), strategy(), exec(), 1, ks);
        assertTrue(gates.notionalWasClamped);
        assertTrue(gates.effectiveMaxNotionalUsd < 1_000_000_000.0);
        long huge = FixedPoint.fromDouble(1500.0); // exceeds the ABSOLUTE_MAX_NOTIONAL_USD ceiling of 1000
        assertFalse(gates.canFire(0, huge, 1_000_000L));
    }

    @Test
    void perTriangleCooldownBlocksRapidRefire() {
        KillSwitch ks = new KillSwitch(new Portfolio(FixedPoint.fromDouble(100.0)), FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 5, 30, 250), strategy(), exec(), 2, ks);
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
        RiskGates gates = new RiskGates(risk(200.0, 1, 30, 0), strategy(), exec(), 1, ks);
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
        RiskGates gates = new RiskGates(risk(200.0, 100, 2, 0), strategy(), exec(), 1, ks);
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
    void killSwitchTripBlocksEverything() {
        Portfolio p = new Portfolio(FixedPoint.fromDouble(100.0));
        KillSwitch ks = new KillSwitch(p, FixedPoint.fromDouble(50.0), 3);
        RiskGates gates = new RiskGates(risk(200.0, 5, 30, 0), strategy(), exec(), 1, ks);
        assertTrue(gates.canFire(0, FixedPoint.fromDouble(10.0), 1L));
        p.applyRealizedPnl(FixedPoint.fromDouble(-60.0)); // equity 100 -> 40, below the $50 floor
        ks.checkEquityFloor();
        assertTrue(ks.tripped());
        assertFalse(gates.canFire(0, FixedPoint.fromDouble(10.0), 2L));
    }
}

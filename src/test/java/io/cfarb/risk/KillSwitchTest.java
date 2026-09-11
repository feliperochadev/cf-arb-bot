package io.cfarb.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.state.Portfolio;
import io.cfarb.util.FixedPoint;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * cf-arb-bot-review-plan.md Tier 1 step 1.8: the kill switch previously only flipped a boolean.
 * {@code BotService}'s wiring of journal/metric/log-line effects onto the trip listener is not
 * exercised here (it needs a live network startup that CLAUDE.md's Q10 forbids in an automated
 * test) -- this covers the listener contract {@code KillSwitch} itself owns: fired exactly once,
 * on the CAS-winning transition, and a listener failure never masks the trip.
 */
class KillSwitchTest {

    @Test
    void tripListenerFiresExactlyOnceOnTheWinningTransition() {
        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(100.0));
        KillSwitch killSwitch = new KillSwitch(portfolio, FixedPoint.fromDouble(50.0), 3);
        AtomicInteger listenerCalls = new AtomicInteger();
        AtomicReference<String> lastReason = new AtomicReference<>();
        killSwitch.setTripListener(reason -> {
            listenerCalls.incrementAndGet();
            lastReason.set(reason);
        });

        killSwitch.recordFailure("boom-1");
        killSwitch.recordFailure("boom-2");
        killSwitch.recordFailure("boom-3"); // 3rd consecutive failure -- trips (maxConsecutiveFailures=3)
        killSwitch.recordFailure("boom-4"); // already tripped -- must not fire the listener again

        assertTrue(killSwitch.tripped());
        assertEquals(1, listenerCalls.get(), "the listener must fire exactly once, on the winning transition only");
        assertTrue(lastReason.get().contains("boom-3"), "the reason recorded must be the one that actually tripped it");
    }

    @Test
    void aThrowingListenerNeverPreventsTheTripFromLatching() {
        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(100.0));
        KillSwitch killSwitch = new KillSwitch(portfolio, FixedPoint.fromDouble(50.0), 1);
        killSwitch.setTripListener(reason -> {
            throw new RuntimeException("operator alerting is down");
        });

        killSwitch.recordFailure("single-failure-trips-immediately");

        assertTrue(killSwitch.tripped(), "a broken listener must never mask the trip itself");
    }

    @Test
    void equityFloorBreachTripsAndInvokesTheListener() {
        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(100.0));
        KillSwitch killSwitch = new KillSwitch(portfolio, FixedPoint.fromDouble(50.0), 3);
        AtomicInteger listenerCalls = new AtomicInteger();
        killSwitch.setTripListener(reason -> listenerCalls.incrementAndGet());

        portfolio.applyRealizedPnl(FixedPoint.fromDouble(-60.0)); // 100 -> 40, below the $50 floor
        killSwitch.checkEquityFloor();

        assertTrue(killSwitch.tripped());
        assertEquals(1, listenerCalls.get());
    }

    @Test
    void aSuccessfulCycleResetsTheConsecutiveFailureCounter() {
        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(100.0));
        KillSwitch killSwitch = new KillSwitch(portfolio, FixedPoint.fromDouble(50.0), 3);

        killSwitch.recordFailure("f1");
        killSwitch.recordFailure("f2");
        killSwitch.recordSuccess(); // resets the counter
        killSwitch.recordFailure("f3");
        killSwitch.recordFailure("f4");

        assertTrue(!killSwitch.tripped(), "only 2 consecutive failures since the last success -- must not trip yet");
    }

    // --- PRE-LIVE-PLAN.md P1-4(a): the second, looser no-fill counter -----------------------

    @Test
    void twentyFourConsecutiveNoFillsDoNotTripButTheTwentyFifthDoes() {
        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(100.0));
        KillSwitch killSwitch = new KillSwitch(portfolio, FixedPoint.fromDouble(50.0), 3, 25);

        for (int i = 0; i < 24; i++) {
            killSwitch.recordNoFill("no-fill-" + i);
        }
        assertTrue(!killSwitch.tripped(), "24 consecutive no-fills must not trip (max-consecutive-no-fill=25)");

        killSwitch.recordNoFill("no-fill-24");
        assertTrue(killSwitch.tripped(), "the 25th consecutive no-fill must trip");
        assertTrue(killSwitch.tripReason().contains("consecutive-no-fill"));
    }

    @Test
    void threeRealFailuresStillTripAtTheirOwnLooserThreshold() {
        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(100.0));
        KillSwitch killSwitch = new KillSwitch(portfolio, FixedPoint.fromDouble(50.0), 3, 25);

        killSwitch.recordFailure("f1");
        killSwitch.recordFailure("f2");
        assertTrue(!killSwitch.tripped());
        killSwitch.recordFailure("f3");
        assertTrue(killSwitch.tripped(), "3 real failures must still trip at max-consecutive-failures, unaffected by "
                + "the separate no-fill counter");
    }

    @Test
    void aRealFailureResetsTheNoFillCounterTooSoItCannotStayArmedBehindIt() {
        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(100.0));
        KillSwitch killSwitch = new KillSwitch(portfolio, FixedPoint.fromDouble(50.0), 100, 3);

        killSwitch.recordNoFill("nf1");
        killSwitch.recordNoFill("nf2"); // 2 of 3 toward the no-fill trip
        killSwitch.recordFailure("real-failure"); // must reset the no-fill counter too
        killSwitch.recordNoFill("nf3");
        killSwitch.recordNoFill("nf4"); // only 2 since the failure -- must not trip

        assertTrue(!killSwitch.tripped(), "recordFailure must reset the no-fill counter, not just its own");
    }

    @Test
    void recordSuccessResetsBothCounters() {
        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(100.0));
        KillSwitch killSwitch = new KillSwitch(portfolio, FixedPoint.fromDouble(50.0), 3, 3);

        killSwitch.recordFailure("f1");
        killSwitch.recordNoFill("nf1");
        killSwitch.recordSuccess();
        killSwitch.recordFailure("f2");
        killSwitch.recordFailure("f3");
        killSwitch.recordNoFill("nf2");

        assertTrue(!killSwitch.tripped(), "recordSuccess must reset both counters, not just consecutiveFailures");
    }

    @Test
    void nonPositiveMaxConsecutiveNoFillRefusesToBoot() {
        Portfolio portfolio = new Portfolio(FixedPoint.fromDouble(100.0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new KillSwitch(portfolio, FixedPoint.fromDouble(50.0), 3, 0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new KillSwitch(portfolio, FixedPoint.fromDouble(50.0), 3, -1));
    }
}

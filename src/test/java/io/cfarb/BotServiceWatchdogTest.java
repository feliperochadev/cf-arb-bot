package io.cfarb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.cfarb.BotService.WatchdogAction;
import io.cfarb.BotService.WatchdogDecision;
import org.junit.jupiter.api.Test;

/**
 * Third-pass review finding (M3): {@code BotService#decideWatchdogAction} is the pure state machine
 * behind the feed watchdog's escalation counter, extracted specifically so this could be tested
 * without a live Vert.x/WebSocket harness. Reproduces the exact bug found: {@code forceReconnect()}'s
 * own side effect ({@code books.resetAll()}) made {@code anyWarmed} false on the tick right after a
 * forced reconnect, which the previous inline logic misread as "healthy" and used to silently reset
 * the escalation counter to 0 -- making the kill-switch trip unreachable for a feed that stayed dark
 * after reconnecting.
 */
class BotServiceWatchdogTest {

    private static final int TRIP_THRESHOLD = 3;

    @Test
    void freshDataNeverEscalates() {
        WatchdogDecision d = BotService.decideWatchdogAction(true, false, 0, TRIP_THRESHOLD);
        assertEquals(WatchdogAction.NONE, d.action());
        assertEquals(0, d.nextConsecutiveFeedDeadDetections());
    }

    @Test
    void neverWarmedAtAllDoesNothingOnAFreshProcess() {
        // Startup: no book has ticked yet, and we've never escalated -- L2Book's own "not yet warm,
        // not stale" semantics, nothing to do.
        WatchdogDecision d = BotService.decideWatchdogAction(false, false, 0, TRIP_THRESHOLD);
        assertEquals(WatchdogAction.NONE, d.action());
        assertEquals(0, d.nextConsecutiveFeedDeadDetections());
    }

    @Test
    void staleBookWhileConnectedEscalatesAndForcesAReconnect() {
        WatchdogDecision d = BotService.decideWatchdogAction(true, true, 0, TRIP_THRESHOLD);
        assertEquals(WatchdogAction.RECONNECT, d.action());
        assertEquals(1, d.nextConsecutiveFeedDeadDetections());
        assertEquals("sustained-stale-book", d.reason());
    }

    @Test
    void theRegressionScenario_darknessAfterOurOwnForcedReconnectKeepsEscalatingInsteadOfResetting() {
        // Tick 1: stale book detected while connected -- escalates to 1, forces a reconnect.
        WatchdogDecision tick1 = BotService.decideWatchdogAction(true, true, 0, TRIP_THRESHOLD);
        assertEquals(WatchdogAction.RECONNECT, tick1.action());
        assertEquals(1, tick1.nextConsecutiveFeedDeadDetections());

        // forceReconnect()'s closeHandler runs books.resetAll() -- every book's updateCount drops to
        // 0, so the NEXT tick observes anyWarmed=false (not "healthy": the reconnect hasn't yet
        // proven itself). THIS is the exact input shape that used to silently reset the counter.
        WatchdogDecision tick2 = BotService.decideWatchdogAction(false, false,
                tick1.nextConsecutiveFeedDeadDetections(), TRIP_THRESHOLD);
        assertEquals(WatchdogAction.RECONNECT, tick2.action(),
                "must keep escalating, not silently reset, when every book goes dark right after our "
                        + "OWN forced reconnect");
        assertEquals(2, tick2.nextConsecutiveFeedDeadDetections());
        assertEquals("post-reconnect-still-dark", tick2.reason());

        // Tick 3: still nothing -- crosses the trip threshold.
        WatchdogDecision tick3 = BotService.decideWatchdogAction(false, false,
                tick2.nextConsecutiveFeedDeadDetections(), TRIP_THRESHOLD);
        assertEquals(WatchdogAction.RECONNECT_AND_TRIP, tick3.action(),
                "3 consecutive dead detections must reach the trip threshold -- this is the exact "
                        + "reachability the previous form silently defeated");
        assertEquals(3, tick3.nextConsecutiveFeedDeadDetections());
    }

    @Test
    void recoveryAfterAForcedReconnectClearsTheEscalation() {
        // Same start as the regression scenario, but this time the reconnect actually works and a
        // book starts ticking fresh again before the trip threshold is reached.
        WatchdogDecision tick1 = BotService.decideWatchdogAction(true, true, 0, TRIP_THRESHOLD);
        assertEquals(1, tick1.nextConsecutiveFeedDeadDetections());

        WatchdogDecision tick2 = BotService.decideWatchdogAction(true, false,
                tick1.nextConsecutiveFeedDeadDetections(), TRIP_THRESHOLD);
        assertEquals(WatchdogAction.NONE, tick2.action(), "confirmed fresh data must clear the escalation");
        assertEquals(0, tick2.nextConsecutiveFeedDeadDetections());
    }

    @Test
    void tripThresholdIsExclusiveOfTheFirstDetection() {
        // Exactly TRIP_THRESHOLD consecutive detections must trip; TRIP_THRESHOLD - 1 must not.
        int before = TRIP_THRESHOLD - 1;
        WatchdogDecision atThreshold = BotService.decideWatchdogAction(true, true, before, TRIP_THRESHOLD);
        assertEquals(WatchdogAction.RECONNECT_AND_TRIP, atThreshold.action());

        WatchdogDecision belowThreshold = BotService.decideWatchdogAction(true, true, before - 1, TRIP_THRESHOLD);
        assertEquals(WatchdogAction.RECONNECT, belowThreshold.action());
    }
}

package io.cfarb.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.metrics.BotMetrics;
import org.junit.jupiter.api.Test;

/**
 * Pure render test for the opt-in console activity summary — same "test the extracted pure function,
 * not a Vert.x harness" approach as {@link io.cfarb.BotServiceWatchdogTest}. Asserts the counter
 * deltas, the derived {@code near-miss} figure, and the per-second rate arithmetic.
 */
class ActivityReportTest {

    private static BotMetrics.Snapshot snap(long framesReceived, long framesDropped, long detected,
            long fired, long unfillable, long cyclesOk, long cyclesBroken, long queueDrops,
            long journalDrops, long suppressed, long trips, long expired) {
        return new BotMetrics.Snapshot(framesReceived, framesDropped, detected, fired, unfillable,
                cyclesOk, cyclesBroken, queueDrops, journalDrops, suppressed, trips, expired);
    }

    private static ActivityReport.View view() {
        return new ActivityReport.View(true, true, false, 9, 9, 100.42, 0.42, 100.0, 4, 11, 812);
    }

    @Test
    void rendersDeltasRatesAndDerivedNearMiss() {
        BotMetrics.Snapshot before = snap(10_000, 0, 1000, 0, 400, 0, 0, 0, 0, 100, 0, 0);
        BotMetrics.Snapshot after = snap(20_040, 0, 4400, 2, 1600, 2, 0, 0, 0, 1500, 0, 0);

        String out = ActivityReport.render(before, after, 5000, view());

        assertTrue(out.contains("frames 20040 (+2008/s)"), out); // 10040 / 5s
        assertTrue(out.contains("evaluated 3400 (+680/s)"), out); // 3400 / 5s
        assertTrue(out.contains("unfillable 1200"), out);
        assertTrue(out.contains("near-miss 3398"), out); // 3400 evaluated - 2 fired
        assertTrue(out.contains("FIRED 2"), out);
        assertTrue(out.contains("cycles 2 ok / 0 broken"), out);
        assertTrue(out.contains("equity $100.42"), out);
        assertTrue(out.contains("pnl +0.42%"), out);
        assertTrue(out.contains("books 9/9 warm"), out);
        assertTrue(out.contains("full-cycle p50 812us"), out);
        assertTrue(out.contains("1400 reject events sampled out"), out); // 1500 - 100
    }

    @Test
    void zeroDeltaWindowIsQuietButStillPrints() {
        BotMetrics.Snapshot s = snap(20_040, 0, 4400, 2, 1600, 2, 0, 0, 0, 1500, 0, 0);

        String out = ActivityReport.render(s, s, 5000, view());

        assertTrue(out.contains("evaluated 0 (+0/s)"), out);
        assertTrue(out.contains("FIRED 0"), out);
        assertFalse(out.contains("sampled out"), out); // no suppressed delta -> no note line
    }

    @Test
    void disconnectedFeedIsCalledOut() {
        BotMetrics.Snapshot s = snap(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        ActivityReport.View disconnected =
                new ActivityReport.View(true, false, false, 0, 9, 100.0, 0.0, 100.0, 0, 0, 0);

        String out = ActivityReport.render(s, s, 5000, disconnected);

        assertTrue(out.contains("feed      DISCONNECTED"), out);
        assertTrue(out.contains("books 0/9 warm"), out);
    }
}

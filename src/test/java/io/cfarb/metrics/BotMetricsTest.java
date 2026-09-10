package io.cfarb.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** {@link BotMetrics#snapshot()} must reflect every counter increment — it is what the opt-in
 * console activity report diffs to produce per-second rates. */
class BotMetricsTest {

    @Test
    void snapshotReflectsCounterIncrements() {
        BotMetrics metrics = new BotMetrics(new SimpleMeterRegistry());

        metrics.recordFrameReceived();
        metrics.recordFrameReceived();
        metrics.recordFrameReceived();
        metrics.recordOpportunityDetected();
        metrics.recordOpportunityDetected();
        metrics.recordOpportunityFired();
        metrics.recordOpportunityRejectedUnfillable();
        metrics.recordCycleCompleted();
        metrics.recordJournalSuppressed();
        metrics.recordIntentExpired();

        BotMetrics.Snapshot s = metrics.snapshot();
        assertEquals(3, s.framesReceived());
        assertEquals(2, s.opportunitiesDetected());
        assertEquals(1, s.opportunitiesFired());
        assertEquals(1, s.opportunitiesRejectedUnfillable());
        assertEquals(1, s.cyclesCompleted());
        assertEquals(1, s.journalSuppressed());
        assertEquals(1, s.intentsExpired());
        assertEquals(0, s.framesDropped());
        assertEquals(0, s.cyclesBroken());
    }

    @Test
    void freshRegistrySnapshotIsAllZero() {
        BotMetrics metrics = new BotMetrics(new SimpleMeterRegistry());
        BotMetrics.Snapshot s = metrics.snapshot();
        assertEquals(0, s.framesReceived());
        assertEquals(0, s.opportunitiesDetected());
        assertEquals(0, s.riskTrips());
    }
}

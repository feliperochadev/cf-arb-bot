package io.cfarb.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.HdrHistogram.ConcurrentHistogram;

/**
 * Micrometer counters (following {@code recorder-service.metrics.RecorderMetrics}'s pattern:
 * pre-resolved at startup so the hot path is a lock-free increment) plus HdrHistogram latency
 * tracking for {@code /api/v1/latency} (cf-arb-bot-plan.md §5.1, §8: "frame-arrival to decision
 * under 100µs... instrumentation, not a guess — how we later decide whether Rust would buy
 * anything").
 */
@ApplicationScoped
public class BotMetrics {

    @Inject
    MeterRegistry registry;

    private io.micrometer.core.instrument.Counter framesReceived;
    private io.micrometer.core.instrument.Counter framesDropped;
    private io.micrometer.core.instrument.Counter opportunitiesDetected;
    private io.micrometer.core.instrument.Counter opportunitiesFired;
    private io.micrometer.core.instrument.Counter opportunitiesRejectedUnfillable;
    private io.micrometer.core.instrument.Counter cyclesCompleted;
    private io.micrometer.core.instrument.Counter cyclesBroken;
    private io.micrometer.core.instrument.Counter orderQueueDrops;
    private io.micrometer.core.instrument.Counter journalDrops;
    private io.micrometer.core.instrument.Counter riskTrips;

    // ConcurrentHistogram: written from the detector thread and the executor thread, read from the
    // HTTP worker thread serving /api/v1/latency.
    private final ConcurrentHistogram frameToDecisionNanos = new ConcurrentHistogram(3);
    private final ConcurrentHistogram decisionToLeg1AckNanos = new ConcurrentHistogram(3);
    private final ConcurrentHistogram fullCycleNanos = new ConcurrentHistogram(3);

    /** CDI-managed default constructor -- {@link #registry} is injected and {@link #init()} runs
     * via {@code @PostConstruct}. */
    public BotMetrics() {
    }

    /** Direct construction for tests that need a real {@link BotMetrics} outside a CDI container
     * (cf-arb-bot-review-plan.md "Tests to add": {@code CycleExecutorTest}) -- e.g.
     * {@code new BotMetrics(new SimpleMeterRegistry())}. */
    public BotMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        this.registry = registry;
        init();
    }

    @PostConstruct
    void init() {
        framesReceived = registry.counter("cfarb.frames.received");
        framesDropped = registry.counter("cfarb.frames.dropped");
        opportunitiesDetected = registry.counter("cfarb.opportunities.detected");
        opportunitiesFired = registry.counter("cfarb.opportunities.fired");
        opportunitiesRejectedUnfillable = registry.counter("cfarb.opportunities.rejected_unfillable");
        cyclesCompleted = registry.counter("cfarb.cycles.completed");
        cyclesBroken = registry.counter("cfarb.cycles.broken");
        orderQueueDrops = registry.counter("cfarb.order_queue.drops");
        journalDrops = registry.counter("cfarb.journal.drops");
        riskTrips = registry.counter("cfarb.risk.trips");
    }

    public void recordFrameReceived() { framesReceived.increment(); }
    public void recordFrameDropped() { framesDropped.increment(); }
    public void recordOpportunityDetected() { opportunitiesDetected.increment(); }
    public void recordOpportunityFired() { opportunitiesFired.increment(); }
    public void recordOpportunityRejectedUnfillable() { opportunitiesRejectedUnfillable.increment(); }
    public void recordCycleCompleted() { cyclesCompleted.increment(); }
    public void recordCycleBroken() { cyclesBroken.increment(); }
    public void recordOrderQueueDrop() { orderQueueDrops.increment(); }
    public void recordJournalDrop() { journalDrops.increment(); }
    public void recordRiskTrip() { riskTrips.increment(); }

    public void recordFrameToDecisionNanos(long nanos) { if (nanos >= 0) frameToDecisionNanos.recordValue(nanos); }
    public void recordDecisionToLeg1AckNanos(long nanos) { if (nanos >= 0) decisionToLeg1AckNanos.recordValue(nanos); }
    public void recordFullCycleNanos(long nanos) { if (nanos >= 0) fullCycleNanos.recordValue(nanos); }

    public ConcurrentHistogram frameToDecisionHistogram() { return frameToDecisionNanos; }
    public ConcurrentHistogram decisionToLeg1AckHistogram() { return decisionToLeg1AckNanos; }
    public ConcurrentHistogram fullCycleHistogram() { return fullCycleNanos; }
}

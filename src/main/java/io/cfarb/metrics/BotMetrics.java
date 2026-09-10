package io.cfarb.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private io.micrometer.core.instrument.Counter journalSuppressed;
    private io.micrometer.core.instrument.Counter riskTrips;
    private io.micrometer.core.instrument.Counter intentsExpired;

    // JOURNAL-TUNING-TASK.md T3: per-triangle / per-symbol stale-leg skip counters. Pre-resolved
    // into flat arrays by initRuntimeCounters() (called once at startup) so the detector hot path
    // is a lock-free array-indexed increment, never a tagged-counter map lookup. Null until wired
    // (unit tests that construct BotMetrics directly): the record* methods no-op in that case.
    private Counter[] detectorStaleSkipByTriangle;
    private Counter[] detectorStaleSkipLegBySymbol;
    // JOURNAL-TUNING-TASK.md T1b: crossed-book episode counter, pre-resolved by symbol index.
    private Counter[] bookCrossedBySymbol;
    // JOURNAL-TUNING-TASK.md T1c: crossed-latch self-heal resets, keyed by "symbol|reason". Lazily
    // resolved -- book resets happen minutes-to-hours apart and only on the Vert.x watchdog thread,
    // never the hot path.
    private final Map<String, Counter> bookResetCounters = new ConcurrentHashMap<>();
    private List<String> symbolNamesForCounters = List.of();

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
        // Third-pass review finding: a REJECT candidate the detector deliberately did not journal
        // because that triangle already journaled one inside cf-bot.journal.reject-sample-ms. This
        // is sampling, not loss -- but it is still counted, so "how many candidates did we actually
        // see" never has to be inferred from the NDJSON line count. See
        // strategy.OpportunityDetector#journalReject.
        journalSuppressed = registry.counter("cfarb.journal.suppressed");
        riskTrips = registry.counter("cfarb.risk.trips");
        // REVIEW.md MED-10: an OrderIntent the executor thread dequeued too late to act on
        // (cf-bot.exec.max-intent-age-ms) -- see exec.CycleExecutor#execute.
        intentsExpired = registry.counter("cfarb.intents.expired");
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
    public void recordJournalSuppressed() { journalSuppressed.increment(); }
    public void recordRiskTrip() { riskTrips.increment(); }
    public void recordIntentExpired() { intentsExpired.increment(); }

    /**
     * JOURNAL-TUNING-TASK.md T1b/T3: pre-resolve the per-triangle / per-symbol tagged counters into
     * flat arrays. Called once from {@code BotService} after the triangle and book registries are
     * built, before the detector or feed watchdog run. Safe to call again (idempotent-ish — it just
     * rebuilds the arrays).
     */
    public void initRuntimeCounters(List<String> triangleNames, List<String> symbolNames) {
        this.symbolNamesForCounters = List.copyOf(symbolNames);
        detectorStaleSkipByTriangle = new Counter[triangleNames.size()];
        for (int i = 0; i < triangleNames.size(); i++) {
            detectorStaleSkipByTriangle[i] = registry.counter("cfarb.detector.stale_skip",
                    "triangle", triangleNames.get(i));
        }
        detectorStaleSkipLegBySymbol = new Counter[symbolNames.size()];
        bookCrossedBySymbol = new Counter[symbolNames.size()];
        for (int i = 0; i < symbolNames.size(); i++) {
            detectorStaleSkipLegBySymbol[i] = registry.counter("cfarb.detector.stale_skip_leg",
                    "symbol", symbolNames.get(i));
            bookCrossedBySymbol[i] = registry.counter("cfarb.book.crossed", "symbol", symbolNames.get(i));
        }
    }

    /** JOURNAL-TUNING-TASK.md T3: a triangle skipped because a leg was stale/untrusted/crossed —
     * the single most common {@code OpportunityDetector.evaluate} outcome, previously uncounted. */
    public void recordDetectorStaleSkip(int triangleIndex) {
        if (detectorStaleSkipByTriangle != null) detectorStaleSkipByTriangle[triangleIndex].increment();
    }

    /** JOURNAL-TUNING-TASK.md T3: attributes a stale-skip to the FIRST failing leg's symbol. */
    public void recordDetectorStaleSkipLeg(int symbolIndex) {
        if (detectorStaleSkipLegBySymbol != null) detectorStaleSkipLegBySymbol[symbolIndex].increment();
    }

    /** JOURNAL-TUNING-TASK.md T1b: a book first went crossed (one increment per crossed episode,
     * not per tick). */
    public void recordBookCrossed(int symbolIndex) {
        if (bookCrossedBySymbol != null) bookCrossedBySymbol[symbolIndex].increment();
    }

    /** JOURNAL-TUNING-TASK.md T1c: a book was force-{@code reset()} to recover from a crossed latch. */
    public void recordBookReset(int symbolIndex, String reason) {
        String symbol = symbolIndex >= 0 && symbolIndex < symbolNamesForCounters.size()
                ? symbolNamesForCounters.get(symbolIndex) : String.valueOf(symbolIndex);
        bookResetCounters.computeIfAbsent(symbol + "|" + reason,
                k -> registry.counter("cfarb.book.reset", "symbol", symbol, "reason", reason)).increment();
    }

    public void recordFrameToDecisionNanos(long nanos) { if (nanos >= 0) frameToDecisionNanos.recordValue(nanos); }
    public void recordDecisionToLeg1AckNanos(long nanos) { if (nanos >= 0) decisionToLeg1AckNanos.recordValue(nanos); }
    public void recordFullCycleNanos(long nanos) { if (nanos >= 0) fullCycleNanos.recordValue(nanos); }

    public ConcurrentHistogram frameToDecisionHistogram() { return frameToDecisionNanos; }
    public ConcurrentHistogram decisionToLeg1AckHistogram() { return decisionToLeg1AckNanos; }
    public ConcurrentHistogram fullCycleHistogram() { return fullCycleNanos; }

    /** Immutable point-in-time read of every counter, for the opt-in console activity report
     * ({@code io.cfarb.observability.ActivityReport}) — the Vert.x timer diffs two of these to
     * render per-second rates without exposing a dozen live-counter getters. Micrometer
     * {@code Counter.count()} returns a {@code double}; the counters here only ever increment by
     * whole numbers, so the cast to {@code long} is exact for any realistic run length. */
    public Snapshot snapshot() {
        return new Snapshot(
                (long) framesReceived.count(), (long) framesDropped.count(),
                (long) opportunitiesDetected.count(), (long) opportunitiesFired.count(),
                (long) opportunitiesRejectedUnfillable.count(), (long) cyclesCompleted.count(),
                (long) cyclesBroken.count(), (long) orderQueueDrops.count(),
                (long) journalDrops.count(), (long) journalSuppressed.count(),
                (long) riskTrips.count(), (long) intentsExpired.count());
    }

    public record Snapshot(long framesReceived, long framesDropped, long opportunitiesDetected,
            long opportunitiesFired, long opportunitiesRejectedUnfillable, long cyclesCompleted,
            long cyclesBroken, long orderQueueDrops, long journalDrops, long journalSuppressed,
            long riskTrips, long intentsExpired) {
    }
}

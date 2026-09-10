package io.cfarb.observability;

import io.cfarb.metrics.BotMetrics;
import java.util.Locale;

/**
 * Pure renderer for the opt-in rolling console activity summary
 * ({@code cf-bot.observability.console-report}). Extracted from the Vert.x timer callback in
 * {@code BotService} so it can be unit-tested without a Vert.x/WebSocket harness — the same
 * "pure, unit-tested decision function" shape as {@code BotService.decideWatchdogAction}.
 *
 * <p>The report diffs two {@link BotMetrics.Snapshot}s taken {@code intervalMs} apart and renders
 * per-second rates. Counter derivations (the detector only exposes a few counters on its
 * allocation-free hot path):
 * <ul>
 *   <li>{@code evaluated} — fillable candidates that cleared the cheap risk gates =
 *       Δ{@code opportunitiesDetected} (incremented in {@code OpportunityDetector.evaluate} right
 *       after the {@code EdgeCalculator} call, before the edge-threshold check).</li>
 *   <li>{@code unfillable} — the displayed ladder could not fill some leg in full =
 *       Δ{@code opportunitiesRejectedUnfillable}.</li>
 *   <li>{@code near-miss} — fillable, but net edge ≤ {@code min-net-bps + slippage-buffer-bps} ≈
 *       Δ{@code opportunitiesDetected} − Δ{@code opportunitiesFired}.</li>
 *   <li>{@code FIRED} — Δ{@code opportunitiesFired}.</li>
 * </ul>
 * Candidates rejected earlier — a stale/crossed/untrusted leg, or {@code RiskGates.canFire}
 * (kill switch, clock skew, cooldown, open-cycle cap, cycles/minute) — are intentionally not
 * counted anywhere on the hot path, so they do not appear here; the footnote line says so.
 */
public final class ActivityReport {

    private ActivityReport() {
    }

    /** Feed/account/latency values read off the hot path alongside the counter snapshot. */
    public record View(boolean dryRun, boolean wsConnected, boolean wsChurning,
            int booksWarm, int booksTotal, double equityUsd, double pnlPct, double seedUsd,
            long frameToDecisionP50Us, long frameToDecisionP99Us, long fullCycleP50Us) {
    }

    public static String render(BotMetrics.Snapshot before, BotMetrics.Snapshot after,
            long intervalMs, View v) {
        double seconds = intervalMs / 1000.0;

        long dFrames = after.framesReceived() - before.framesReceived();
        long dDropped = after.framesDropped() - before.framesDropped();
        long dEvaluated = after.opportunitiesDetected() - before.opportunitiesDetected();
        long dUnfillable = after.opportunitiesRejectedUnfillable() - before.opportunitiesRejectedUnfillable();
        long dFired = after.opportunitiesFired() - before.opportunitiesFired();
        long dNearMiss = Math.max(0, dEvaluated - dFired);
        long dSuppressed = after.journalSuppressed() - before.journalSuppressed();
        long dCyclesOk = after.cyclesCompleted() - before.cyclesCompleted();
        long dCyclesBroken = after.cyclesBroken() - before.cyclesBroken();
        long dExpired = after.intentsExpired() - before.intentsExpired();
        long dQueueDrops = after.orderQueueDrops() - before.orderQueueDrops();
        long dJournalDrops = after.journalDrops() - before.journalDrops();

        String feedState = !v.wsConnected() ? "DISCONNECTED" : v.wsChurning() ? "churning" : "connected";

        StringBuilder sb = new StringBuilder(512);
        sb.append(String.format(Locale.ROOT, "cf-arb-bot · %s · last %.1fs%n",
                v.dryRun() ? "dry-run" : "LIVE", seconds));
        sb.append(String.format(Locale.ROOT,
                "  feed      %s   frames %d (%+d/s)   books %d/%d warm   dropped %d%n",
                feedState, after.framesReceived(), perSec(dFrames, seconds),
                v.booksWarm(), v.booksTotal(), dDropped));
        sb.append(String.format(Locale.ROOT,
                "  strategy  evaluated %d (%+d/s)   unfillable %d   near-miss %d   FIRED %d%n",
                dEvaluated, perSec(dEvaluated, seconds), dUnfillable, dNearMiss, dFired));
        sb.append(String.format(Locale.ROOT,
                "  exec      cycles %d ok / %d broken   intents expired %d   queue drops %d   journal drops %d%n",
                dCyclesOk, dCyclesBroken, dExpired, dQueueDrops, dJournalDrops));
        sb.append(String.format(Locale.ROOT,
                "  account   equity $%.2f   pnl %+.2f%%   (seed $%.2f)%n",
                v.equityUsd(), v.pnlPct(), v.seedUsd()));
        sb.append(String.format(Locale.ROOT,
                "  latency   frame→decision p50 %dus / p99 %dus    full-cycle p50 %dus",
                v.frameToDecisionP50Us(), v.frameToDecisionP99Us(), v.fullCycleP50Us()));
        if (dSuppressed > 0) {
            sb.append(String.format(Locale.ROOT,
                    "%n  note      %d reject events sampled out (cf-bot.journal.reject-sample-ms); "
                            + "stale-leg / risk-gate rejects are not counted", dSuppressed));
        }
        return sb.toString();
    }

    private static long perSec(long delta, double seconds) {
        return seconds <= 0 ? delta : Math.round(delta / seconds);
    }
}

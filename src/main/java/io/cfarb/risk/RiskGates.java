package io.cfarb.risk;

import io.cfarb.config.BotConfig;
import io.cfarb.util.FixedPoint;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Every trading gate, all fail CLOSED (security rule S5), all hard-clamped in code as well as
 * config (rule S6) — cf-arb-bot-plan.md §5.5. Four of these (the startup notional clamp, the
 * per-triangle cooldown CAS, the sliding-window cycles/minute cap, the open-cycle cap) are ported
 * patterns from {@code cf-trader.trader.Strategist}/{@code Orderer}; the equity floor and
 * consecutive-failure trip live in {@link KillSwitch} instead, since those need to latch
 * permanently rather than gate a single decision.
 *
 * <p><b>Threading:</b> {@link #canFire} and {@link #claim} are called ONLY from the single Netty
 * event-loop thread that owns the MEXC depth WebSocket connection (12 configured symbols is well
 * under MEXC's 30-streams-per-connection cap, so this bot uses exactly one connection — see
 * {@code cf-bot.symbols}) — the per-triangle cooldown array and the cycles/minute ring buffer are
 * therefore single-writer and need no synchronization, mirroring
 * {@code cf-trader.trader.Orderer}'s single-consumer {@code ArrayDeque} rate limiter. Only
 * {@link #openCycles} and {@code KillSwitch}'s fields cross threads (detector thread claims, the
 * dedicated executor thread — see {@code exec.CycleExecutor} — releases), so those alone are
 * atomic.
 */
public final class RiskGates {

    /** The S6 code ceiling. {@code cf-bot.risk.absolute-max-notional-usd} (the operator-tunable
     * backstop that {@code max-notional-usd} is clamped to) is ITSELF clamped to this at
     * construction, so "a hard cap enforced in code, not only config" (CLAUDE.md non-negotiable #3)
     * still holds even if BOTH notional keys are fat-fingered. Sized generously ($1M) so it never
     * binds a deliberate config — it exists only to stop the classic {@code 10^9} typo from reaching
     * a real order. This is the one value that is NOT config, by design. */
    private static final double ABSOLUTE_MAX_NOTIONAL_CEILING = 1_000_000.0;

    private final KillSwitch killSwitch;
    private final boolean dryRun;
    private final long maxNotionalFixed;
    public final boolean notionalWasClamped;
    public final double effectiveMaxNotionalUsd;
    /** {@code cf-bot.risk.absolute-max-notional-usd} after the code ceiling clamp — the value
     * {@link #effectiveMaxNotionalUsd} was actually capped against. */
    public final double effectiveAbsoluteMaxNotionalUsd;
    /** True when {@code cf-bot.risk.absolute-max-notional-usd} itself exceeded
     * {@link #ABSOLUTE_MAX_NOTIONAL_CEILING} and was clamped in code — a fat-fingered SAFETY limit,
     * worth a louder startup flag than the ordinary {@link #notionalWasClamped}. */
    public final boolean absoluteMaxWasClamped;

    private final int maxOpenCycles;
    private final long cooldownNanos;
    private final long maxBookAgeNanos;
    private final long clockSkewToleranceNanos;
    // REVIEW.md MED-01: sampled by BotService's periodic RTT-corrected clock-skew timer and gated
    // here -- previously sampled and surfaced in readiness/API only, never actually consulted before
    // firing. volatile: written from the Vert.x event-loop thread's periodic timer callback, read
    // from the detector thread's canFire() hot path.
    private volatile boolean clockSkewKnown;
    private volatile long clockSkewNanos;
    // Third-pass review finding (M7): the nanoTime() a skew sample was taken at, so canFire() can
    // tell a STALE sample from a fresh one -- see the class-level constant and canFire()'s usage.
    private volatile long clockSkewSampleNanos = Long.MIN_VALUE / 2; // never-sampled sentinel

    /** Third-pass review finding (M7): {@code clockSkewKnown} used to latch {@code true} permanently
     * on the FIRST successful sample and never expire -- if {@code /api/v3/time} later became
     * unreachable (an egress rule change, a sustained MEXC outage) or every subsequent sample kept
     * getting discarded by {@code BotService}'s own implausible-RTT filter, {@code canFire} would
     * keep trusting an hours-old skew value forever, and the live-mode fail-closed branch below
     * ("no sample yet -> refuse to fire") would then be reachable only during the brief window
     * before the very first sample ever landed. {@code BotService} samples every
     * {@code WARMUP_PERIOD_MS} (60s); a sample older than 3x that cadence is treated exactly like
     * "never sampled" -- fail closed in live mode, same as the true never-sampled case. */
    private static final long CLOCK_SKEW_SAMPLE_MAX_AGE_NANOS = 180_000_000_000L; // 180s

    private final AtomicLongArray lastFireNanosByTriangle;
    private final AtomicInteger openCycles = new AtomicInteger(0);

    // Sliding cycles-per-minute window -- single-writer (detector thread) ring buffer, see class doc.
    private final long[] ringTimestampsNanos;
    private final int maxCyclesPerMinute;
    private int ringHead;
    private int ringCount;

    public RiskGates(BotConfig.RiskConfig riskConfig, BotConfig.StrategyConfig strategyConfig,
                      BotConfig.ExecConfig execConfig, int triangleCount, KillSwitch killSwitch,
                      boolean dryRun) {
        this.killSwitch = killSwitch;
        this.dryRun = dryRun;

        // cf-arb-bot-review-plan.md Tier 2 step 2.5: a non-positive limit here previously widened
        // silently to 1 via Math.max(1, ...) instead of being rejected -- fail closed (S5) on a
        // misconfiguration instead of quietly substituting a default the operator never chose.
        double configuredMaxNotional = riskConfig.maxNotionalUsd();
        if (configuredMaxNotional <= 0) {
            throw new IllegalStateException(
                    "cf-bot.risk.max-notional-usd must be > 0, got " + configuredMaxNotional);
        }
        double configuredAbsoluteMax = riskConfig.absoluteMaxNotionalUsd();
        if (configuredAbsoluteMax <= 0) {
            throw new IllegalStateException(
                    "cf-bot.risk.absolute-max-notional-usd must be > 0, got " + configuredAbsoluteMax);
        }
        // S6: the operator-tunable backstop is ITSELF clamped in code, so a typo in either notional
        // key is still caught before it can size a real order.
        double absoluteMax = Math.min(configuredAbsoluteMax, ABSOLUTE_MAX_NOTIONAL_CEILING);
        this.absoluteMaxWasClamped = absoluteMax != configuredAbsoluteMax;
        this.effectiveAbsoluteMaxNotionalUsd = absoluteMax;

        double clamped = Math.min(configuredMaxNotional, absoluteMax);
        this.notionalWasClamped = clamped != configuredMaxNotional;
        this.effectiveMaxNotionalUsd = clamped;
        this.maxNotionalFixed = FixedPoint.fromDouble(clamped);

        if (riskConfig.maxOpenCycles() <= 0) {
            throw new IllegalStateException(
                    "cf-bot.risk.max-open-cycles must be > 0, got " + riskConfig.maxOpenCycles());
        }
        this.maxOpenCycles = riskConfig.maxOpenCycles();
        this.cooldownNanos = riskConfig.cycleCooldownMs() * 1_000_000L;
        this.maxBookAgeNanos = strategyConfig.maxBookAgeMs() * 1_000_000L;
        // Third-pass review finding (M7): the previous tolerance was the FULL recvWindow -- exactly
        // the boundary at which MEXC starts returning -1021 ("timestamp outside recvWindow"), so a
        // skew that passed this gate could still be one jitter spike (or the time between this check
        // and the request actually reaching MEXC) away from rejection. Halved for real margin before
        // the venue's own hard boundary; recvWindow itself is unchanged (still sent as-is to MEXC).
        this.clockSkewToleranceNanos = (execConfig.recvWindowMs() * 1_000_000L) / 2;

        if (riskConfig.maxCyclesPerMinute() <= 0) {
            throw new IllegalStateException(
                    "cf-bot.risk.max-cycles-per-minute must be > 0, got " + riskConfig.maxCyclesPerMinute());
        }
        this.maxCyclesPerMinute = riskConfig.maxCyclesPerMinute();
        this.ringTimestampsNanos = new long[maxCyclesPerMinute];
        this.lastFireNanosByTriangle = new AtomicLongArray(Math.max(1, triangleCount));
        // AtomicLongArray defaults every slot to 0, which is indistinguishable from "fired at
        // System.nanoTime()==0" -- nanoTime's origin is arbitrary per JVM and unit tests commonly
        // use small nanoTime values for readability, so 0 is not a safe "never fired" sentinel.
        // Seed every slot far enough in the past that (now - sentinel) always clears any
        // realistic cooldown, while staying well clear of Long.MIN_VALUE to avoid subtraction
        // overflow.
        long neverFiredSentinel = Long.MIN_VALUE / 2;
        for (int i = 0; i < lastFireNanosByTriangle.length(); i++) {
            lastFireNanosByTriangle.set(i, neverFiredSentinel);
        }
    }

    /**
     * Hot-path pre-fire check on the detector thread. Does NOT claim the fire slot (a candidate
     * can fail a downstream check after this returns true — e.g. {@link io.cfarb.strategy.EdgeCalculator}
     * finding the ladder can't actually fill the size) — call {@link #claim} only immediately
     * before dispatching to the executor, so a doomed candidate never burns real cooldown/rate-limit
     * budget.
     */
    public boolean canFire(int triangleIndex, long candidateNotionalFixed, long nowNanos) {
        if (killSwitch.tripped()) {
            return false;
        }
        // REVIEW.md MED-01/MED-02: an unknown skew (no successful sample yet) fails closed ONLY in
        // live mode -- this gate exists to protect real signed requests against MEXC's -1021
        // ("timestamp outside recvWindow"), which dry-run never sends. Blocking paper trading
        // because this host happens to have no route to MEXC's time endpoint would be a false
        // safety, not a real one. A CONFIRMED skew beyond tolerance blocks in both modes -- it is
        // useful, actionable information about this host's clock regardless of trading mode.
        //
        // Third-pass review finding (M7): a sample older than CLOCK_SKEW_SAMPLE_MAX_AGE_NANOS is
        // treated exactly like "never sampled" -- clockSkewKnown otherwise latches true forever on
        // the FIRST successful sample, so a later loss of connectivity to MEXC's time endpoint would
        // keep this gate trusting an arbitrarily stale value rather than degrading back to the same
        // fail-closed-in-live-mode behavior a fresh JVM with no sample yet gets.
        boolean skewSampleFresh = clockSkewKnown
                && (nowNanos - clockSkewSampleNanos) <= CLOCK_SKEW_SAMPLE_MAX_AGE_NANOS;
        if (skewSampleFresh) {
            if (Math.abs(clockSkewNanos) > clockSkewToleranceNanos) {
                return false;
            }
        } else if (!dryRun) {
            return false;
        }
        if (openCycles.get() >= maxOpenCycles) {
            return false;
        }
        if (candidateNotionalFixed <= 0 || candidateNotionalFixed > maxNotionalFixed) {
            return false;
        }
        if (nowNanos - lastFireNanosByTriangle.get(triangleIndex) < cooldownNanos) {
            return false;
        }
        if (ringCount >= maxCyclesPerMinute) {
            long oldest = ringTimestampsNanos[ringHead];
            if (nowNanos - oldest < 60_000_000_000L) {
                return false; // at the cycles-per-minute cap
            }
        }
        return true;
    }

    /** Claim the fire slot and mark a cycle as open. Executor must call {@link #onCycleFinished()}
     * exactly once when the cycle completes or is unwound, or the open-cycle slot leaks forever. */
    public void claim(int triangleIndex, long nowNanos) {
        lastFireNanosByTriangle.set(triangleIndex, nowNanos);
        int idx = (ringHead + ringCount) % maxCyclesPerMinute;
        ringTimestampsNanos[idx] = nowNanos;
        if (ringCount < maxCyclesPerMinute) {
            ringCount++;
        } else {
            ringHead = (ringHead + 1) % maxCyclesPerMinute;
        }
        openCycles.incrementAndGet();
    }

    /** Called by the executor thread when a cycle finishes, successfully or not. Clamped at zero
     * (cf-arb-bot-review-plan.md Tier 1 step 1.6) as defense in depth: {@code exec.CycleExecutor}
     * now has exactly one release owner per cycle, but a future double-release must still fail
     * closed (block firing) rather than driving the count negative and permanently defeating
     * {@code max-open-cycles}. */
    public void onCycleFinished() {
        openCycles.updateAndGet(n -> Math.max(0, n - 1));
    }

    public int openCycleCount() {
        return openCycles.get();
    }

    public boolean isBookFresh(long bookAgeNanos) {
        return bookAgeNanos <= maxBookAgeNanos;
    }

    public long clockSkewToleranceNanos() {
        return clockSkewToleranceNanos;
    }

    /** Called from {@code BotService}'s periodic clock-skew timer (RTT-corrected -- see
     * {@code BotService#scheduleWarmUpAndClockSkew}'s javadoc for the midpoint-estimate fix,
     * REVIEW.md MED-02) whenever a sample is successfully taken. Records the sample TIME (this
     * class's own {@code nanoTime()}, not the caller's) so {@link #canFire} can detect a sample
     * that has since gone stale (third-pass review finding M7) -- see
     * {@link #CLOCK_SKEW_SAMPLE_MAX_AGE_NANOS}'s javadoc. */
    public void updateClockSkew(long skewNanos) {
        this.clockSkewNanos = skewNanos;
        this.clockSkewSampleNanos = System.nanoTime();
        this.clockSkewKnown = true;
    }
}

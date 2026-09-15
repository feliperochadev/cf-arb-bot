package io.cfarb.strategy;

import io.cfarb.model.Side;

/**
 * PRE-LIVE-PLAN.md P0-1 ("Fix B") / cf-arb-bot-plan.md §5.3.2: dry-run-only bookkeeping of ladder
 * depth already claimed by a fired-but-not-yet-executed candidate, so a paper fill actually consumes
 * the liquidity it modelled. Until this existed, the SAME unconsumed book state was re-read by every
 * dry-run evaluation — the {@code usdt-sol-btc-rev} burst "bought" 19.32 SOL three times out of a
 * single level holding 40.27 (58 SOL modelled out of 40 actually available).
 *
 * <p><b>Keyed by {@code (symbolIndex, side, ladder POSITION)}, not by price or a stable per-level
 * id.</b> A level's position can drift when the ladder shifts (an insertion ahead of it), which
 * would silently "forget" an in-flight claim early — accepted deliberately, the same direction as
 * every other approximation here ({@link Sizer#MAX_TRACKED_LEVELS}, the TTL below): this ledger
 * UNDER-reports consumption, never over-reports it, so the failure mode is a candidate re-reading
 * slightly more (real, still-available) depth than it should, never being refused depth that is
 * genuinely there. The primary safety net is the {@code writeSeq} check below, which catches the
 * much more common case (a level rewritten in place, position unchanged) exactly.
 *
 * <p><b>Recording is dispatch-time, not evaluation-time</b> — {@code OpportunityDetector} calls
 * {@link #record} ONLY immediately after a successful {@code orderQueue.offer()}, the identical
 * place and condition {@code DUPLICATE-FIRE-TASK.md}'s Fix A stores its own signature. A candidate
 * that is merely evaluated (rejected, below threshold, gross-bailed, or suppressed as a duplicate)
 * must never consume anything — {@link Sizer#fillLeg} only ever calls {@link #consumed}, a pure
 * read, so a rejected walk cannot poison the book for the next real candidate.
 *
 * <p><b>Expiry.</b> An entry is dead — contributes {@code 0} — the instant the venue rewrites that
 * level ({@code L2Book}'s per-level {@code writeSeq}, monotonic even across {@code reset()}, has
 * advanced past the value recorded at claim time) or a backstop TTL
 * ({@code cf-bot.dry-run.consumption-ttl-ms}) elapses, whichever comes first. A rewritten level is
 * genuinely new liquidity; the TTL is the backstop for a level nobody ever rewrites again.
 *
 * <p><b>Live mode:</b> {@code BotService} constructs this only when {@code cf-bot.dry-run=true};
 * every call site elsewhere threads a nullable reference and is a no-op when it is {@code null}.
 * Live execution is never simulated.
 *
 * <p>R1: one {@code long[nSymbols * 2 * MAX_TRACKED_LEVELS]} triple-array (qty, writeSeq, stampNanos)
 * allocated once at construction. {@link #record} and {@link #consumed} are array-indexed, no
 * boxing, no {@code Map} — {@link #record} runs off the hot evaluation path (dispatch-time only, see
 * above), {@link #consumed} runs inside {@link Sizer}'s per-level walk and must stay allocation-free
 * there too.
 */
public final class ConsumptionLedger {

    private final int nSymbols;
    private final long ttlNanos;

    private final long[] qtyFixed;
    private final long[] writeSeqAtClaim;
    private final long[] stampNanos;

    public ConsumptionLedger(int nSymbols, long ttlMs) {
        this.nSymbols = Math.max(1, nSymbols);
        // S6: a non-positive TTL is a misconfiguration -- BotService validates cf-bot.dry-run.consumption-ttl-ms
        // before ever constructing this (see BotService#resolveConsumptionLedger); asserted again
        // here so a future direct caller can't bypass that check.
        if (ttlMs <= 0) {
            throw new IllegalStateException("cf-bot.dry-run.consumption-ttl-ms must be > 0, got " + ttlMs);
        }
        this.ttlNanos = ttlMs * 1_000_000L;
        int size = this.nSymbols * 2 * Sizer.MAX_TRACKED_LEVELS;
        this.qtyFixed = new long[size];
        this.writeSeqAtClaim = new long[size];
        this.stampNanos = new long[size];
    }

    private int index(int symbolIndex, Side side, int levelIndex) {
        return (symbolIndex * 2 + (side == Side.BID ? 0 : 1)) * Sizer.MAX_TRACKED_LEVELS + levelIndex;
    }

    /** Record {@code qtyFixed} claimed at {@code (symbolIndex, side, levelIndex)}, which carried
     * {@code writeSeqAtRecordTime} at the moment of claim. Accumulates on top of an existing LIVE
     * (same {@code writeSeq}, not TTL-expired) entry — a second fire against a level the first fire
     * only partially drained must add to, not replace, what is already claimed there. A stale entry
     * (different {@code writeSeq}, or TTL-expired) is reset to this claim rather than accumulated
     * onto, since the prior claim's target liquidity is already gone (rewritten) or too old to trust. */
    public void record(int symbolIndex, Side side, int levelIndex, long writeSeqAtRecordTime,
                        long qtyFixedClaimed, long nowNanos) {
        if (levelIndex < 0 || levelIndex >= Sizer.MAX_TRACKED_LEVELS || symbolIndex < 0 || symbolIndex >= nSymbols) {
            return; // deeper/out-of-range walks go unmodelled -- conservative, see class javadoc
        }
        int idx = index(symbolIndex, side, levelIndex);
        boolean stale = writeSeqAtClaim[idx] != writeSeqAtRecordTime
                || (nowNanos - stampNanos[idx]) > ttlNanos;
        if (stale) {
            qtyFixed[idx] = 0;
            writeSeqAtClaim[idx] = writeSeqAtRecordTime;
        }
        qtyFixed[idx] += qtyFixedClaimed;
        stampNanos[idx] = nowNanos;
    }

    /** Quantity already claimed at {@code (symbolIndex, side, levelIndex)}, or {@code 0} if there is
     * no live claim there — {@code currentWriteSeq} doesn't match what was recorded (the venue
     * rewrote the level), the claim has aged past the TTL, or nothing was ever recorded. Called from
     * {@link Sizer}'s per-level walk; must stay allocation-free (rule R1). */
    public long consumed(int symbolIndex, Side side, int levelIndex, long currentWriteSeq, long nowNanos) {
        if (levelIndex < 0 || levelIndex >= Sizer.MAX_TRACKED_LEVELS || symbolIndex < 0 || symbolIndex >= nSymbols) {
            return 0L;
        }
        int idx = index(symbolIndex, side, levelIndex);
        if (writeSeqAtClaim[idx] != currentWriteSeq) {
            return 0L; // the venue rewrote this level -- genuinely new liquidity
        }
        if ((nowNanos - stampNanos[idx]) > ttlNanos) {
            return 0L; // backstop: nobody touched this level since, don't trust an old claim forever
        }
        return qtyFixed[idx];
    }

    /** Clears every claim for {@code symbolIndex} on both sides — mirrors {@code L2Book#reset()}'s
     * shape, though nothing currently calls this: a book reset already bumps every surviving level's
     * {@code writeSeq} monotonically (see {@code L2Book}'s own javadoc), so {@link #consumed}'s
     * {@code writeSeq} check alone already invalidates every stale claim after a reset without this.
     * Kept for callers that want an explicit, immediate clear rather than waiting on the natural
     * writeSeq/TTL expiry. */
    public void clear(int symbolIndex, Side side) {
        if (symbolIndex < 0 || symbolIndex >= nSymbols) {
            return;
        }
        int base = (symbolIndex * 2 + (side == Side.BID ? 0 : 1)) * Sizer.MAX_TRACKED_LEVELS;
        for (int i = 0; i < Sizer.MAX_TRACKED_LEVELS; i++) {
            qtyFixed[base + i] = 0;
            writeSeqAtClaim[base + i] = 0;
            stampNanos[base + i] = 0;
        }
    }
}

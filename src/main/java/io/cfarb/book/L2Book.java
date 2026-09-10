package io.cfarb.book;

import io.cfarb.feed.MexcDepthDecoder;

/**
 * One symbol's reconstructed L2 book, ported from {@code cf-arb-poc/cfarb/book.py}'s semantics
 * (warm-up gate, crossed-book detection, soft version-chain gap detection, prune-to-bounded-size)
 * but backed by SORTED PRIMITIVE ARRAYS instead of a {@code dict[float,float]} — bids descending
 * by price, asks ascending — so the top-of-book and the full ladder are always immediately
 * available with no per-request sort (the Python version sorts on every {@code top_levels()} call;
 * a live bot calls that on every candidate tick). No boxing, no {@code Map} allocation per update.
 *
 * <p>Book reconstruction from diffs is a SUBSET of the true book (deletions arrive explicitly as
 * qty=0, so it never contains a stale level) — the reconstructed spread is always >= the true
 * spread, so any edge this book supports is real, understating rather than overstating arbitrage
 * (cf-arb-poc-plan.md §2.4, carried into this bot's honest-limits list).
 *
 * <p>Not thread-safe by design: one {@code L2Book} per symbol, mutated ONLY by the single Netty
 * event-loop thread that owns the MEXC WebSocket connection (hot-path rule: zero locks). Readers
 * that GATE A TRADING DECISION (the strategy evaluation that runs inline on that same thread) never
 * race with a writer from a different thread — see {@code BotService}'s threading model
 * (cf-arb-bot-plan.md §5.1). Accepted exception: read-only diagnostic snapshots
 * ({@code api.BotApiResource}, {@code api.ReadinessCheck}, {@code BotService}'s feed watchdog) read
 * simple boolean/long fields from other threads for advisory reporting only — a stale or torn read
 * there means a monitoring endpoint is briefly out of date, never a bad trade, and is the same
 * trade-off most systems make for health/metrics endpoints against hot-path state. A second,
 * declared exception (cf-arb-bot-review-plan.md second pass, Tier A4 / CLAUDE.md non-negotiable
 * #5): {@link #topBidFixed()}/{@link #topAskFixed()} are read by the {@code cf-arb-executor}
 * thread to price an emergency unwind reversal ({@code exec.Unwinder}) when the executor thread's
 * own pricing (the failed cycle's stale entry-boundary price) has already been shown not to work.
 * This is still never a trading DECISION on that thread -- it is pricing a recovery action for a
 * cycle that has already failed, using the most recent price this book has published, the same
 * trade-off class as the diagnostic reads above.
 */
public final class L2Book {

    /** Below Python's PRUNE_TRIGGER(400)/PRUNE_KEEP(200) is where a live top-9-symbol book sits in
     * practice; sized generously above any level count observed in real captured MEXC frames. */
    private static final int CAPACITY = 512;
    private static final int PRUNE_TRIGGER = 400;
    private static final int PRUNE_KEEP = 200;

    private final long[] bidPx = new long[CAPACITY];
    private final long[] bidQty = new long[CAPACITY];
    private int bidCount;

    private final long[] askPx = new long[CAPACITY];
    private final long[] askQty = new long[CAPACITY];
    private int askCount;

    private final long warmupUpdates;
    private final long warmupNanos;
    /** JOURNAL-TUNING-TASK.md T1c: how long (ns) a book may stay crossed before {@link #apply}
     * force-{@link #reset}s it. {@code 0} disables the self-heal (pre-JOURNAL-TUNING behavior). */
    private final long maxCrossedNanos;
    private long updateCount;
    private long warmStartNanos = -1;
    private boolean trusted;
    private long lastToVersion = -1;
    private long lastUpdateNanos = -1;
    private long lastSendTimeMs = -1;

    // JOURNAL-TUNING-TASK.md T1b/T1c: nanoTime the book FIRST became crossed (-1 = not currently
    // crossed). Written only on the Netty event-loop thread in apply()/reset(); read from the
    // Vert.x feed-watchdog timer thread for the crossed-book WARN and from the Quarkus HTTP worker
    // for GET /api/v1/books -- the same advisory cross-thread read the watchdog already makes of
    // updateCount()/ageNanos() (see the class javadoc). volatile so those readers see a recent value.
    private volatile long crossedSinceNanos = -1;
    /** JOURNAL-TUNING-TASK.md T1c: monotonic count of crossed-latch self-heal resets. The feed
     * watchdog diffs this per symbol to journal a {@code book_reset} event without doing any work
     * on the hot path. volatile for the same cross-thread-read reason as {@link #crossedSinceNanos}. */
    private volatile long crossedResetCount;

    // cf-arb-bot-review-plan.md (second pass) Tier A4: published top-of-book, read by the executor
    // thread ONLY for pricing an emergency unwind reversal (exec.Unwinder) -- never for a trading
    // DECISION, which stays exclusively on this book-owning Netty event-loop thread via the normal
    // askPxAt(0)/bidPxAt(0) accessors. Two independent volatiles rather than one allocated pair: a
    // torn read (bid from one update, ask from the very next) yields two individually-valid RECENT
    // prices milliseconds apart on a book that ticks every 10-40ms -- the unwind cross buffer and
    // the venue's own PERCENT_PRICE_BY_SIDE clamp both have far more slack than that, so a torn
    // read is harmless here in a way it would not be for a hot-path fire decision.
    private volatile long topBidFixed = Long.MIN_VALUE;
    private volatile long topAskFixed = Long.MIN_VALUE;

    /** Self-heal disabled ({@code maxCrossedMs = 0}) — kept for the many unit tests that construct a
     * book directly and do not exercise JOURNAL-TUNING-TASK.md T1c. */
    public L2Book(long warmupUpdates, long warmupSeconds) {
        this(warmupUpdates, warmupSeconds, 0L);
    }

    public L2Book(long warmupUpdates, long warmupSeconds, long maxCrossedMs) {
        this.warmupUpdates = warmupUpdates;
        this.warmupNanos = warmupSeconds * 1_000_000_000L;
        this.maxCrossedNanos = maxCrossedMs > 0 ? maxCrossedMs * 1_000_000L : 0L;
    }

    public void reset() {
        bidCount = 0;
        askCount = 0;
        updateCount = 0;
        warmStartNanos = -1;
        trusted = false;
        lastToVersion = -1;
        topBidFixed = Long.MIN_VALUE;
        topAskFixed = Long.MIN_VALUE;
        crossedSinceNanos = -1;
    }

    /**
     * Apply one decoded MEXC depth push. {@code nowNanos} must be {@code System.nanoTime()} at
     * frame receipt (never a venue-supplied timestamp — security rule S14). Returns false if a
     * version-chain gap was detected (the book was reset before applying this update, matching
     * {@code book.py}'s "reset on gap" contract) so the caller can count it.
     */
    public boolean apply(MexcDepthDecoder.DepthFrame f, long nowNanos) {
        // JOURNAL-TUNING-TASK.md T1c self-heal: a book that has been crossed longer than the grace
        // period is reset HERE, on its owning Netty event-loop thread, and rebuilt from this frame
        // onward -- rather than latching permanently until a version-chain gap forces a reset
        // (JOURNAL-BPS-ANALYSIS.md §5.5: BTCUSDT stays crossed for hours). reset() clears trusted,
        // so isTrusted() re-gates every triangle touching this symbol until it re-warms (rule S5).
        if (maxCrossedNanos > 0 && crossedSinceNanos >= 0
                && nowNanos - crossedSinceNanos >= maxCrossedNanos) {
            reset();
            crossedResetCount++;
        }
        boolean gap = lastToVersion >= 0 && f.fromVersion >= 0 && f.fromVersion > lastToVersion + 1;
        if (gap) {
            reset();
        }
        for (int i = 0; i < f.bidCount; i++) {
            applyLevel(true, f.bidPx[i], f.bidQty[i]);
        }
        for (int i = 0; i < f.askCount; i++) {
            applyLevel(false, f.askPx[i], f.askQty[i]);
        }
        if (f.toVersion >= 0) {
            lastToVersion = f.toVersion;
        }
        if (warmStartNanos < 0) {
            warmStartNanos = nowNanos;
        }
        updateCount++;
        lastUpdateNanos = nowNanos;
        if (f.sendTimeMs >= 0) {
            lastSendTimeMs = f.sendTimeMs;
        }
        // Tier A4: publish AFTER the level arrays are updated above, so a reader never observes a
        // top-of-book price that is stale relative to what apply() just wrote.
        topBidFixed = bidCount > 0 ? bidPx[0] : Long.MIN_VALUE;
        topAskFixed = askCount > 0 ? askPx[0] : Long.MIN_VALUE;
        // JOURNAL-TUNING-TASK.md T1b/T1c: record the crossed-state transition. Only written on an
        // actual edge (clean -> crossed, or crossed -> clean), so the steady state costs one
        // comparison and no volatile store (rule R1).
        if (isCrossed()) {
            if (crossedSinceNanos < 0) {
                crossedSinceNanos = nowNanos;
            }
        } else if (crossedSinceNanos >= 0) {
            crossedSinceNanos = -1;
        }
        maybePromote(nowNanos);
        return !gap;
    }

    private void applyLevel(boolean isBid, long px, long qty) {
        long[] pxArr = isBid ? bidPx : askPx;
        long[] qtyArr = isBid ? bidQty : askQty;
        int count = isBid ? bidCount : askCount;

        int idx = findIndex(pxArr, count, px, isBid);
        boolean found = idx < count && pxArr[idx] == px;

        if (qty == 0) {
            if (found) {
                System.arraycopy(pxArr, idx + 1, pxArr, idx, count - idx - 1);
                System.arraycopy(qtyArr, idx + 1, qtyArr, idx, count - idx - 1);
                if (isBid) bidCount--; else askCount--;
            }
            return;
        }
        if (found) {
            qtyArr[idx] = qty;
            return;
        }
        // insert a new level at idx, shifting the tail right
        if (count >= CAPACITY) {
            return; // capacity exceeded (shouldn't happen given pruning below) — drop, don't corrupt
        }
        System.arraycopy(pxArr, idx, pxArr, idx + 1, count - idx);
        System.arraycopy(qtyArr, idx, qtyArr, idx + 1, count - idx);
        pxArr[idx] = px;
        qtyArr[idx] = qty;
        if (isBid) {
            bidCount++;
            pruneIfNeeded(true);
        } else {
            askCount++;
            pruneIfNeeded(false);
        }
    }

    /** Binary search insertion index in a sorted array (bids descending, asks ascending). */
    private static int findIndex(long[] arr, int count, long px, boolean descending) {
        int lo = 0, hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            boolean goLeft = descending ? arr[mid] < px : arr[mid] > px;
            if (arr[mid] == px) return mid;
            if (goLeft) hi = mid; else lo = mid + 1;
        }
        return lo;
    }

    private void pruneIfNeeded(boolean isBid) {
        int count = isBid ? bidCount : askCount;
        if (count <= PRUNE_TRIGGER) return;
        if (isBid) {
            bidCount = PRUNE_KEEP; // already sorted best-first; truncate the far side
        } else {
            askCount = PRUNE_KEEP;
        }
    }

    private void maybePromote(long nowNanos) {
        if (trusted) return;
        long elapsedNanos = warmStartNanos < 0 ? 0 : nowNanos - warmStartNanos;
        if (updateCount >= warmupUpdates || elapsedNanos >= warmupNanos) {
            trusted = true;
        }
    }

    public boolean isTrusted() {
        return trusted;
    }

    public boolean isCrossed() {
        return bidCount > 0 && askCount > 0 && bidPx[0] >= askPx[0];
    }

    public boolean isEmpty() {
        return bidCount == 0 || askCount == 0;
    }

    public long bestBidPx() {
        return bidCount > 0 ? bidPx[0] : Long.MIN_VALUE;
    }

    public long bestBidQty() {
        return bidCount > 0 ? bidQty[0] : 0;
    }

    public long bestAskPx() {
        return askCount > 0 ? askPx[0] : Long.MIN_VALUE;
    }

    public long bestAskQty() {
        return askCount > 0 ? askQty[0] : 0;
    }

    public int bidLevelCount() {
        return bidCount;
    }

    public int askLevelCount() {
        return askCount;
    }

    /** Level i (0 = best). No bounds check on the hot path — caller must respect levelCount(). */
    public long bidPxAt(int i) {
        return bidPx[i];
    }

    public long bidQtyAt(int i) {
        return bidQty[i];
    }

    public long askPxAt(int i) {
        return askPx[i];
    }

    public long askQtyAt(int i) {
        return askQty[i];
    }

    /** Local receive-time age in nanoseconds — the ONLY clock this bot gates on (rule S14). */
    public long ageNanos(long nowNanos) {
        return lastUpdateNanos < 0 ? Long.MAX_VALUE : nowNanos - lastUpdateNanos;
    }

    /** Venue's own event timestamp (protobuf sendTime field), for cross-leg skew MEASUREMENT only
     * — never for gating/cooldown decisions (security rule S14 narrow exception, cf-arb-bot-plan.md §5.1). */
    public long lastSendTimeMs() {
        return lastSendTimeMs;
    }

    public long updateCount() {
        return updateCount;
    }

    /** JOURNAL-TUNING-TASK.md T1a/T1b: nanoseconds this book has been continuously crossed, or 0 if
     * it is not currently crossed. Advisory cross-thread read (feed watchdog / GET /api/v1/books),
     * same trade-off as {@link #updateCount()} — see the class javadoc. */
    public long crossedForNanos(long nowNanos) {
        long since = crossedSinceNanos;
        return since < 0 ? 0L : Math.max(0L, nowNanos - since);
    }

    /** JOURNAL-TUNING-TASK.md T1c: monotonic count of crossed-latch self-heal resets this book has
     * performed. The feed watchdog diffs this per symbol to journal a {@code book_reset} event. */
    public long crossedResetCount() {
        return crossedResetCount;
    }

    /** Published top-of-book, for {@code exec.Unwinder}'s emergency reversal pricing ONLY (Tier
     * A4) -- {@code Long.MIN_VALUE} if no side has ever had a level. See the field javadoc above for
     * why a torn read across the two is an accepted trade-off here specifically. */
    public long topBidFixed() {
        return topBidFixed;
    }

    public long topAskFixed() {
        return topAskFixed;
    }
}

package io.cfarb.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.feed.MexcDepthDecoder;
import org.junit.jupiter.api.Test;

/**
 * cf-arb-bot-review-plan.md Tier 1 step 1.7: books must reset on reconnect (via
 * {@code BookRegistry#resetAll} -> {@link L2Book#reset()}), and the pre-existing version-chain gap
 * detection and crossed-book detection must keep working. Also covers pruning correctness, one of
 * the "highest-risk path tests" the independent review's Minor findings called for.
 */
class L2BookTest {

    private static MexcDepthDecoder.DepthFrame frame(long fromVersion, long toVersion) {
        MexcDepthDecoder.DepthFrame f = new MexcDepthDecoder.DepthFrame();
        f.fromVersion = fromVersion;
        f.toVersion = toVersion;
        f.sendTimeMs = 0;
        return f;
    }

    private static void addBid(MexcDepthDecoder.DepthFrame f, long px, long qty) {
        f.bidPx[f.bidCount] = px;
        f.bidQty[f.bidCount] = qty;
        f.bidCount++;
    }

    private static void addAsk(MexcDepthDecoder.DepthFrame f, long px, long qty) {
        f.askPx[f.askCount] = px;
        f.askQty[f.askCount] = qty;
        f.askCount++;
    }

    @Test
    void warmsUpAfterEnoughUpdatesAndTrustsThereafter() {
        L2Book book = new L2Book(3, 3600);
        assertFalse(book.isTrusted());
        for (int i = 0; i < 3; i++) {
            MexcDepthDecoder.DepthFrame f = frame(-1, -1);
            addBid(f, 100L, 10L);
            addAsk(f, 101L, 10L);
            book.apply(f, System.nanoTime());
        }
        assertTrue(book.isTrusted());
    }

    @Test
    void detectsAVersionChainGapAndResetsOnIt() {
        L2Book book = new L2Book(1, 3600);
        MexcDepthDecoder.DepthFrame f1 = frame(1, 5);
        addBid(f1, 100L, 10L);
        addAsk(f1, 101L, 10L);
        assertTrue(book.apply(f1, System.nanoTime()), "no prior version -- not a gap");
        assertEquals(1, book.bidLevelCount());

        // fromVersion jumps from 5+1=6 expected to 10 -- a genuine gap
        MexcDepthDecoder.DepthFrame f2 = frame(10, 12);
        addBid(f2, 200L, 20L);
        boolean noGap = book.apply(f2, System.nanoTime());
        assertFalse(noGap, "apply() must report the gap");
        // the book was reset before applying f2 -- only f2's own level should be present
        assertEquals(1, book.bidLevelCount());
        assertEquals(200L, book.bidPxAt(0));
    }

    @Test
    void resetClearsLevelsAndUntrustsTheBook() {
        L2Book book = new L2Book(1, 3600);
        MexcDepthDecoder.DepthFrame f = frame(-1, -1);
        addBid(f, 100L, 10L);
        addAsk(f, 101L, 10L);
        book.apply(f, System.nanoTime());
        assertTrue(book.isTrusted());
        assertEquals(1, book.bidLevelCount());

        // cf-arb-bot-review-plan.md Tier 1 step 1.7: BookRegistry#resetAll calls this on every
        // WebSocket disconnect and again before re-subscribing on reconnect.
        book.reset();

        assertFalse(book.isTrusted(), "a reset book must re-warm before being trusted again");
        assertEquals(0, book.bidLevelCount());
        assertEquals(0, book.askLevelCount());
        assertTrue(book.isEmpty());
    }

    @Test
    void detectsACrossedBook() {
        L2Book book = new L2Book(1, 3600);
        MexcDepthDecoder.DepthFrame f = frame(-1, -1);
        addBid(f, 102L, 10L); // bid >= ask -- crossed
        addAsk(f, 101L, 10L);
        book.apply(f, System.nanoTime());
        assertTrue(book.isCrossed());
    }

    @Test
    void prunesTheFarSideWhenLevelCountExceedsTheTrigger() {
        L2Book book = new L2Book(1, 3600);
        // A single decoded push is capped at MAX_LEVELS(50) entries, so build up past
        // PRUNE_TRIGGER(400) across several incremental frames, as the real feed would.
        int total = 401;
        int perFrame = 40;
        for (int start = 0; start < total; start += perFrame) {
            MexcDepthDecoder.DepthFrame f = frame(-1, -1);
            int end = Math.min(start + perFrame, total);
            for (int i = start; i < end; i++) {
                addAsk(f, 1000L + i, 1L);
            }
            if (start == 0) {
                addBid(f, 1L, 1L);
            }
            book.apply(f, System.nanoTime());
        }
        assertEquals(200, book.askLevelCount());
        assertEquals(1000L, book.askPxAt(0), "pruning must keep the best (lowest) ask prices, not arbitrary ones");
    }

    // --- JOURNAL-TUNING-TASK.md T1c/T1d: crossed-latch self-heal ---

    /**
     * Reproduces JOURNAL-BPS-ANALYSIS.md §5.5's pruning hypothesis with hand-built frames (rule
     * Q10 / non-negotiable #8 -- never a live network call): a fast one-way price move where the
     * feed delivers new near-touch levels but NOT an explicit {@code qty=0} for the levels the move
     * left behind. The stale far-side bid survives at index 0 and the book crosses -- and, with
     * {@code maxCrossedMs=0}, never recovers, exactly the one-way latch the session data shows.
     */
    @Test
    void aStalePriceLevelCanCrossTheBookAndWithoutSelfHealItNeverRecovers() {
        L2Book book = new L2Book(1, 3600, 0L); // self-heal disabled
        MexcDepthDecoder.DepthFrame warm = frame(-1, -1);
        addBid(warm, 120_000_00000000L, 1_00000000L);
        addAsk(warm, 120_001_00000000L, 1_00000000L);
        book.apply(warm, 1_000_000_000L);
        assertFalse(book.isCrossed());

        // Price falls hard: new bids/asks ~1% lower arrive, but the 120_000 bid is never deleted.
        for (int i = 1; i <= 5; i++) {
            MexcDepthDecoder.DepthFrame f = frame(-1, -1);
            addBid(f, 118_800_00000000L - i * 100_00000000L, 2_00000000L);
            addAsk(f, 118_801_00000000L - i * 100_00000000L, 2_00000000L);
            book.apply(f, 1_000_000_000L + i * 10_000_000L);
        }
        assertTrue(book.isCrossed(), "stale 120_000 bid vs a ~118_800 ask -- crossed");

        // More frames arrive; the book stays latched crossed forever.
        for (int i = 6; i <= 40; i++) {
            MexcDepthDecoder.DepthFrame f = frame(-1, -1);
            addAsk(f, 118_795_00000000L, 3_00000000L);
            book.apply(f, 1_000_000_000L + i * 10_000_000L);
        }
        assertTrue(book.isCrossed(), "no self-heal path -> permanent per-symbol outage");
    }

    /** With {@code cf-bot.book.max-crossed-ms} set, the same latch is broken: once the book has been
     * crossed longer than the grace period, the next {@code apply()} resets it (untrusting it so it
     * fails closed) and rebuilds from that frame. */
    @Test
    void selfHealResetsABookThatHasBeenCrossedLongerThanTheGracePeriod() {
        L2Book book = new L2Book(10, 3600, 500L); // heal after 500ms crossed; 10 updates to warm
        long t = 1_000_000_000L;

        for (int i = 0; i < 10; i++) {
            MexcDepthDecoder.DepthFrame warm = frame(-1, -1);
            addBid(warm, 100L, 10L);
            addAsk(warm, 101L, 10L);
            book.apply(warm, t);
        }
        assertTrue(book.isTrusted());

        // Cross it: a new bid above the ask, ask left in place.
        MexcDepthDecoder.DepthFrame cross = frame(-1, -1);
        addBid(cross, 105L, 5L);
        book.apply(cross, t + 1_000_000L); // +1ms
        assertTrue(book.isCrossed());
        assertEquals(0, book.crossedResetCount());

        // 400ms later -- still inside the grace period, still crossed, not yet healed.
        MexcDepthDecoder.DepthFrame within = frame(-1, -1);
        addAsk(within, 106L, 7L);
        book.apply(within, t + 401_000_000L);
        assertTrue(book.isCrossed());
        assertEquals(0, book.crossedResetCount());

        // 600ms after it first crossed -- the next apply() force-resets and rebuilds from this frame.
        MexcDepthDecoder.DepthFrame heal = frame(-1, -1);
        addBid(heal, 200L, 3L);
        addAsk(heal, 201L, 3L);
        book.apply(heal, t + 601_000_000L);

        assertEquals(1, book.crossedResetCount(), "one self-heal reset");
        assertFalse(book.isCrossed(), "rebuilt clean from the healing frame");
        assertFalse(book.isTrusted(), "reset untrusts the book so it fails closed while re-warming (S5)");
        assertEquals(200L, book.bidPxAt(0));
        assertEquals(201L, book.askPxAt(0));
    }

    @Test
    void selfHealClearsOnceTheBookUncrossesNormally() {
        L2Book book = new L2Book(1, 3600, 500L);
        long t = 1_000_000_000L;
        MexcDepthDecoder.DepthFrame warm = frame(-1, -1);
        addBid(warm, 100L, 10L);
        addAsk(warm, 101L, 10L);
        book.apply(warm, t);

        MexcDepthDecoder.DepthFrame cross = frame(-1, -1);
        addBid(cross, 105L, 5L);
        book.apply(cross, t + 1_000_000L);
        assertTrue(book.isCrossed());

        // The 105 bid is deleted before the grace period elapses -- book uncrosses, no reset.
        MexcDepthDecoder.DepthFrame fix = frame(-1, -1);
        addBid(fix, 105L, 0L);
        book.apply(fix, t + 100_000_000L);
        assertFalse(book.isCrossed());

        // Re-cross and wait past the grace period from the SECOND cross, not the first.
        MexcDepthDecoder.DepthFrame cross2 = frame(-1, -1);
        addBid(cross2, 110L, 5L);
        book.apply(cross2, t + 200_000_000L);
        MexcDepthDecoder.DepthFrame later = frame(-1, -1);
        addAsk(later, 111L, 1L);
        book.apply(later, t + 400_000_000L); // only 200ms since the re-cross
        assertEquals(0, book.crossedResetCount(), "grace period restarts on each fresh cross");
    }

    @Test
    void applyLevelRemovesAZeroQuantityLevel() {
        L2Book book = new L2Book(1, 3600);
        MexcDepthDecoder.DepthFrame f1 = frame(-1, -1);
        addBid(f1, 100L, 10L);
        addBid(f1, 99L, 5L);
        addAsk(f1, 101L, 10L);
        book.apply(f1, System.nanoTime());
        assertEquals(2, book.bidLevelCount());

        MexcDepthDecoder.DepthFrame f2 = frame(-1, -1);
        addBid(f2, 100L, 0L); // qty=0 means delete this level
        book.apply(f2, System.nanoTime());
        assertEquals(1, book.bidLevelCount());
        assertEquals(99L, book.bidPxAt(0));
    }
}

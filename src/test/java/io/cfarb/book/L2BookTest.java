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

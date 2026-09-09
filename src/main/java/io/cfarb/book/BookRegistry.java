package io.cfarb.book;

import io.cfarb.feed.MexcDepthDecoder;
import java.util.List;

/**
 * symbolIndex -> {@link L2Book}, a flat array indexed by position in the configured symbol list
 * (hot-path rule R3: flat arrays indexed by symbolIndex, not maps). Also resolves a raw protobuf
 * symbol byte-range straight to its index with a linear byte compare — no {@code String}
 * allocation, mirroring {@code cf-trader.fetcher.ExchangeFeed#symbolIdx} exactly. With 9-13
 * symbols this linear scan is a few dozen byte comparisons, cheaper than a hash-map lookup's
 * boxing + hashCode() for this cardinality.
 */
public final class BookRegistry {

    private final String[] symbols;
    private final byte[][] symbolBytes;
    private final L2Book[] books;

    public BookRegistry(List<String> symbols, long warmupUpdates, long warmupSeconds) {
        this.symbols = symbols.toArray(new String[0]);
        this.symbolBytes = new byte[this.symbols.length][];
        this.books = new L2Book[this.symbols.length];
        for (int i = 0; i < this.symbols.length; i++) {
            symbolBytes[i] = this.symbols[i].getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            books[i] = new L2Book(warmupUpdates, warmupSeconds);
        }
    }

    /** Resolve buf[start,end) to a symbol index, or -1 if not in our configured universe. */
    public int indexOf(byte[] buf, int start, int end) {
        int len = end - start;
        outer:
        for (int s = 0; s < symbolBytes.length; s++) {
            byte[] name = symbolBytes[s];
            if (name.length != len) continue;
            for (int i = 0; i < len; i++) {
                if (name[i] != buf[start + i]) continue outer;
            }
            return s;
        }
        return -1;
    }

    public int indexOf(String symbol) {
        for (int i = 0; i < symbols.length; i++) {
            if (symbols[i].equals(symbol)) return i;
        }
        return -1;
    }

    public L2Book book(int symbolIndex) {
        return books[symbolIndex];
    }

    /** Published top-of-book for {@code symbolIndex}, for {@code exec.Unwinder}'s emergency
     * reversal pricing ONLY (Tier A4) -- see {@link L2Book#topBidFixed()}'s javadoc. */
    public long topBidFixed(int symbolIndex) {
        return books[symbolIndex].topBidFixed();
    }

    public long topAskFixed(int symbolIndex) {
        return books[symbolIndex].topAskFixed();
    }

    /** Third-pass review finding (L1): whether {@code symbolIndex}'s book has cleared its warm-up
     * gate -- {@code exec.Unwinder} consults this before trusting a published top-of-book to price
     * an emergency reversal (a book that is still warming up after a reconnect publishes a top that
     * is not yet the real best price). Same accepted cross-thread trade-off as {@link #topBidFixed}. */
    public boolean isTrusted(int symbolIndex) {
        return books[symbolIndex].isTrusted();
    }

    /** Local receive-time age of {@code symbolIndex}'s book, for {@code exec.Unwinder}'s reversal
     * staleness gate (third-pass review finding L1) -- see {@link L2Book#ageNanos}. */
    public long ageNanos(int symbolIndex, long nowNanos) {
        return books[symbolIndex].ageNanos(nowNanos);
    }

    /** Reset every book (cf-arb-bot-review-plan.md Tier 1 step 1.7) -- call on WebSocket disconnect
     * and again immediately before re-subscribing on reconnect. Without this, a stale ladder and
     * version-chain survive a reconnect; if the venue's push channel ever omits version fields, the
     * existing gap-detection reset in {@link L2Book#apply} cannot fire at all, and fresh diffs merge
     * silently into a stale book. {@link L2Book#isTrusted()}'s warm-up gate then naturally re-gates
     * trading until every book has re-warmed on the new connection. */
    public void resetAll() {
        for (L2Book book : books) {
            book.reset();
        }
    }

    public String symbol(int symbolIndex) {
        return symbols[symbolIndex];
    }

    public int symbolCount() {
        return symbols.length;
    }

    /** Convenience entry point: decode straight into the resolved symbol's book. Returns the
     * symbol index that was updated, or -1 if the frame was control traffic or an unknown symbol. */
    public int applyFrame(MexcDepthDecoder.DepthFrame f, long nowNanos) {
        int idx = indexOf(f.sourceBuf, f.symbolStart, f.symbolEnd);
        if (idx < 0) {
            return -1;
        }
        books[idx].apply(f, nowNanos);
        return idx;
    }
}

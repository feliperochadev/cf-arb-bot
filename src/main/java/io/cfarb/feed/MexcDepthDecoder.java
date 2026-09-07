package io.cfarb.feed;

import io.cfarb.util.FixedPoint;

/**
 * Decodes one MEXC {@code spot@public.aggre.depth.v3.api.pb@<symbol>} push frame using
 * {@link ProtobufWalker}, writing straight into a preallocated, reusable {@link DepthFrame} — zero
 * allocation on the hot path (rule R1), no {@code protobuf-java} / generated stubs (rule R11), and
 * strictly iterative (rule S8, no recursion on hostile input). Field map and semantics ported from
 * {@code cf-arb-poc/cfarb/venues/mexc.py}, cross-checked field-for-field against it in
 * {@code MexcDepthDecoderTest} using real captured frames (cf-arb-bot-plan.md §11 item 1):
 *
 * <pre>
 * PushDataV3ApiWrapper:
 *     1: channel (string)          3: symbol (string)      6: sendTime (varint, ms epoch)
 *     313: PublicAggreDepths
 *         1: asks (repeated {1: price string, 2: qty string})
 *         2: bids (repeated {1: price string, 2: qty string})
 *         4: fromVersion (string)  5: toVersion (string)
 * </pre>
 *
 * <p>The connection also carries plain JSON control frames (subscribe acks, {@code {"msg":"PONG"}}
 * keepalive replies) interleaved with these protobuf pushes — {@link #decode} returns {@code false}
 * for anything that isn't a well-formed {@code aggre.depth} push, mirroring
 * {@code cfarb.venues.mexc.parse_depth}'s try/except-returns-None contract; the caller must treat
 * {@code false} as "not a depth frame," never as an error.
 *
 * <p>No native continuity counter is captured on this channel (mirroring recorder-service's
 * SILENCE_ONLY gap-audit choice for MEXC), but {@code fromVersion}/{@code toVersion} give a soft
 * continuity check — {@link io.cfarb.book.L2Book} treats a chain break as a reset, same as
 * {@code cf-arb-poc/cfarb/book.py}.
 */
public final class MexcDepthDecoder {

    private static final int CHANNEL_FIELD = 1;
    private static final int SYMBOL_FIELD = 3;
    private static final int SEND_TIME_FIELD = 6;
    private static final int DEPTHS_FIELD = 313;

    private static final int ASKS_FIELD = 1;
    private static final int BIDS_FIELD = 2;
    private static final int FROM_VERSION_FIELD = 4;
    private static final int TO_VERSION_FIELD = 5;

    private static final int PRICE_FIELD = 1;
    private static final int QTY_FIELD = 2;

    /**
     * Real captured MEXC aggre.depth frames have been observed with 30+ ask levels (a BTCUSDC
     * frame in the fixture set carries 31) — this is NOT a fixed top-N snapshot. Levels beyond
     * this cap are silently dropped, understating depth, which is the same conservative direction
     * (never overstate arbitrage) as every other approximation in this project
     * (cf-arb-poc-plan.md §2.4). Sized generously above every level count observed in fixtures.
     */
    public static final int MAX_LEVELS = 50;

    private MexcDepthDecoder() {
    }

    /** Reusable output buffer for one decode call. Allocate ONE per connection, never per frame. */
    public static final class DepthFrame {
        /** Byte range of the symbol string WITHIN THE CALLER'S BUFFER — valid only until the next
         * decode() call reuses that buffer. Compare with a byte-range symbol table
         * (io.cfarb.book.BookRegistry), never copy to a String on the hot path. */
        public byte[] sourceBuf;
        public int symbolStart, symbolEnd;
        public long sendTimeMs = -1;
        public long fromVersion = -1;
        public long toVersion = -1;
        public final long[] bidPx = new long[MAX_LEVELS];
        public final long[] bidQty = new long[MAX_LEVELS];
        public int bidCount;
        public final long[] askPx = new long[MAX_LEVELS];
        public final long[] askQty = new long[MAX_LEVELS];
        public int askCount;
        public boolean valid;
    }

    /**
     * Decode buf[off, off+len) into {@code out}. {@code top}/{@code sub}/{@code level} are three
     * reusable cursors (top-level message, the embedded PublicAggreDepths message, and one
     * embedded price/qty level message in turn) — allocate all three once per connection.
     *
     * @return true if {@code out} now holds a genuine depth update; false if this frame is control
     *         traffic or malformed (caller must not treat false as an error).
     */
    public static boolean decode(byte[] buf, int off, int len,
                                  ProtobufWalker.Cursor top, ProtobufWalker.Cursor sub,
                                  ProtobufWalker.Cursor level, DepthFrame out) {
        out.valid = false;
        out.bidCount = 0;
        out.askCount = 0;
        out.sendTimeMs = -1;
        out.fromVersion = -1;
        out.toVersion = -1;
        out.symbolStart = 0;
        out.symbolEnd = 0;

        int limit = off + len;
        top.reset(off, limit);
        boolean sawAggreDepthChannel = false;
        int depthsStart = -1;
        int depthsEnd = -1;

        while (ProtobufWalker.nextField(buf, limit, top)) {
            switch (top.fieldNumber) {
                case CHANNEL_FIELD:
                    if (top.wireType == ProtobufWalker.WIRE_LEN
                            && containsAscii(buf, top.lenStart, top.lenEnd, "aggre.depth")) {
                        sawAggreDepthChannel = true;
                    }
                    break;
                case SYMBOL_FIELD:
                    if (top.wireType == ProtobufWalker.WIRE_LEN) {
                        out.symbolStart = top.lenStart;
                        out.symbolEnd = top.lenEnd;
                    }
                    break;
                case SEND_TIME_FIELD:
                    if (top.wireType == ProtobufWalker.WIRE_VARINT) {
                        out.sendTimeMs = top.varintValue;
                    }
                    break;
                case DEPTHS_FIELD:
                    if (top.wireType == ProtobufWalker.WIRE_LEN) {
                        depthsStart = top.lenStart;
                        depthsEnd = top.lenEnd;
                    }
                    break;
                default:
                    // unknown/uninteresting field — skip, already consumed by nextField
            }
        }
        if (top.malformed || !sawAggreDepthChannel || depthsStart < 0 || out.symbolEnd <= out.symbolStart) {
            return false;
        }

        sub.reset(depthsStart, depthsEnd);
        while (ProtobufWalker.nextField(buf, depthsEnd, sub)) {
            switch (sub.fieldNumber) {
                case ASKS_FIELD:
                    if (sub.wireType == ProtobufWalker.WIRE_LEN && out.askCount < MAX_LEVELS) {
                        decodeLevel(buf, sub.lenStart, sub.lenEnd, level, out, false);
                    }
                    break;
                case BIDS_FIELD:
                    if (sub.wireType == ProtobufWalker.WIRE_LEN && out.bidCount < MAX_LEVELS) {
                        decodeLevel(buf, sub.lenStart, sub.lenEnd, level, out, true);
                    }
                    break;
                case FROM_VERSION_FIELD:
                    if (sub.wireType == ProtobufWalker.WIRE_LEN) {
                        long v = parseAsciiLong(buf, sub.lenStart, sub.lenEnd);
                        if (v >= 0) out.fromVersion = v;
                    }
                    break;
                case TO_VERSION_FIELD:
                    if (sub.wireType == ProtobufWalker.WIRE_LEN) {
                        long v = parseAsciiLong(buf, sub.lenStart, sub.lenEnd);
                        if (v >= 0) out.toVersion = v;
                    }
                    break;
                default:
                    // unknown field within PublicAggreDepths — skip
            }
        }
        if (sub.malformed) {
            return false;
        }
        out.sourceBuf = buf;
        out.valid = true;
        return true;
    }

    private static void decodeLevel(byte[] buf, int start, int end, ProtobufWalker.Cursor level,
                                     DepthFrame out, boolean isBid) {
        level.reset(start, end);
        long px = Long.MIN_VALUE;
        long qty = Long.MIN_VALUE;
        while (ProtobufWalker.nextField(buf, end, level)) {
            if (level.fieldNumber == PRICE_FIELD && level.wireType == ProtobufWalker.WIRE_LEN) {
                px = FixedPoint.parse(buf, level.lenStart, level.lenEnd);
            } else if (level.fieldNumber == QTY_FIELD && level.wireType == ProtobufWalker.WIRE_LEN) {
                qty = FixedPoint.parse(buf, level.lenStart, level.lenEnd);
            }
        }
        if (level.malformed || px == Long.MIN_VALUE || qty == Long.MIN_VALUE) {
            return; // malformed level — drop it, don't poison the whole frame (fail closed, locally)
        }
        if (isBid) {
            out.bidPx[out.bidCount] = px;
            out.bidQty[out.bidCount] = qty;
            out.bidCount++;
        } else {
            out.askPx[out.askCount] = px;
            out.askQty[out.askCount] = qty;
            out.askCount++;
        }
    }

    /** ASCII substring containment check over a byte range, no allocation. */
    static boolean containsAscii(byte[] buf, int start, int end, String needle) {
        int nlen = needle.length();
        int hlen = end - start;
        outer:
        for (int i = 0; i <= hlen - nlen; i++) {
            for (int j = 0; j < nlen; j++) {
                if (buf[start + i + j] != (byte) needle.charAt(j)) continue outer;
            }
            return true;
        }
        return false;
    }

    /** Parse an ASCII decimal integer (MEXC's fromVersion/toVersion strings), no allocation. */
    static long parseAsciiLong(byte[] buf, int start, int end) {
        if (start >= end) return -1;
        long v = 0;
        for (int i = start; i < end; i++) {
            int d = buf[i] - '0';
            if (d < 0 || d > 9) return -1;
            v = v * 10 + d;
        }
        return v;
    }

    /** True if buf[symbolStart,symbolEnd) equals the given ASCII symbol string, no allocation. */
    public static boolean symbolEquals(byte[] buf, int symbolStart, int symbolEnd, String symbol) {
        if (symbolEnd - symbolStart != symbol.length()) return false;
        for (int i = 0; i < symbol.length(); i++) {
            if (buf[symbolStart + i] != (byte) symbol.charAt(i)) return false;
        }
        return true;
    }
}

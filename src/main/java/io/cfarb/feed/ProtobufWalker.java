package io.cfarb.feed;

/**
 * Generic protobuf wire-format walker — no {@code .proto} file, no {@code protoc}, no
 * {@code protobuf-java} dependency (hot-path rule R11 bans deserializing venue frames into POJOs
 * with a generic library; hostile-input rule S8 bans recursion on frame parsing, so this is
 * strictly iterative). Ported from {@code cf-arb-poc/cfarb/venues/mexc_pb.py}'s tag/length walker,
 * re-targeted at raw {@code byte[]} so field VALUES stay byte-range references into the original
 * buffer — MEXC transmits prices/quantities as ASCII-digit strings inside the protobuf (confirmed
 * by hex-dumping real captured bytes), so {@link io.cfarb.util.FixedPoint#parse(byte[], int, int)}
 * parses directly from those ranges with zero intermediate {@code String} or {@code byte[]} copy.
 *
 * <p>Usage pattern: allocate exactly ONE {@link Cursor} per WebSocket connection at setup time
 * (never per frame — rule R1), and call {@link #nextField(byte[], int, Cursor)} in a loop bounded
 * by the message's own length-delimited range. A single cursor instance is reused for the top-level
 * message and, via {@link Cursor#reset(int, int)}, for each nested embedded message in turn.
 */
public final class ProtobufWalker {

    public static final int WIRE_VARINT = 0;
    public static final int WIRE_FIXED64 = 1;
    public static final int WIRE_LEN = 2;
    public static final int WIRE_FIXED32 = 5;

    private ProtobufWalker() {
    }

    /**
     * Mutable, reusable cursor over one byte range of a buffer. Never allocated per frame — create
     * one per connection (or one per nesting depth actually used) and {@link #reset} it before
     * walking a new range.
     */
    public static final class Cursor {
        /** Current read position, advanced by {@link #nextField}. */
        public int pos;
        /** Exclusive upper bound of the range currently being walked. */
        public int limit;
        /** Field number of the tag most recently read. */
        public int fieldNumber;
        /** Wire type of the tag most recently read. */
        public int wireType;
        /** For {@link #WIRE_VARINT}: the decoded value. */
        public long varintValue;
        /** For {@link #WIRE_LEN}/{@link #WIRE_FIXED32}/{@link #WIRE_FIXED64}: the byte range
         * [lenStart, lenEnd) of the field's raw content within the source buffer. */
        public int lenStart;
        public int lenEnd;
        /** Set to true by {@link #nextField} if the buffer was truncated or malformed —
         * fail-closed sentinel (rule R14), never an exception thrown from the hot path (rule S8). */
        public boolean malformed;

        public void reset(int pos, int limit) {
            this.pos = pos;
            this.limit = limit;
            this.malformed = false;
        }
    }

    /**
     * Advance {@code c} to the next top-level field within {@code [c.pos, c.limit)} of {@code buf}.
     * Returns {@code false} when the range is exhausted (or malformed — check {@code c.malformed}).
     * Never recurses: embedded messages are walked by pointing a (possibly the same, reset) cursor
     * at {@code [c.lenStart, c.lenEnd)} and calling this again.
     */
    public static boolean nextField(byte[] buf, int limit, Cursor c) {
        if (c.pos >= limit || c.malformed) {
            return false;
        }
        long tag = readVarint(buf, limit, c);
        if (c.malformed) {
            return false;
        }
        c.fieldNumber = (int) (tag >>> 3);
        c.wireType = (int) (tag & 0x7);
        switch (c.wireType) {
            case WIRE_VARINT -> {
                c.varintValue = readVarint(buf, limit, c);
                if (c.malformed) return false;
            }
            case WIRE_LEN -> {
                long len = readVarint(buf, limit, c);
                if (c.malformed || len < 0 || c.pos + len > limit) {
                    c.malformed = true;
                    return false;
                }
                c.lenStart = c.pos;
                c.lenEnd = c.pos + (int) len;
                c.pos = c.lenEnd;
            }
            case WIRE_FIXED64 -> {
                if (c.pos + 8 > limit) { c.malformed = true; return false; }
                c.lenStart = c.pos;
                c.lenEnd = c.pos + 8;
                c.pos = c.lenEnd;
            }
            case WIRE_FIXED32 -> {
                if (c.pos + 4 > limit) { c.malformed = true; return false; }
                c.lenStart = c.pos;
                c.lenEnd = c.pos + 4;
                c.pos = c.lenEnd;
            }
            default -> {
                c.malformed = true; // unsupported wire type — hostile/corrupt input, fail closed
                return false;
            }
        }
        return true;
    }

    private static long readVarint(byte[] buf, int limit, Cursor c) {
        long result = 0;
        int shift = 0;
        while (true) {
            if (c.pos >= limit || shift > 63) {
                c.malformed = true;
                return -1;
            }
            byte b = buf[c.pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
    }
}

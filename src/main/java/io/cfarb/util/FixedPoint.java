package io.cfarb.util;

/**
 * 1e8 fixed-point money, ported from {@code cf-trader/util/FixedPoint} with two fixes made while
 * copying (cf-arb-bot-plan.md §4, "port near-verbatim"):
 *
 * <ol>
 *   <li>{@code mulDiv} now lives here, not privately in a strategy class — cf-trader's own hot-path
 *       rule R2 says {@code FixedPoint.mulDiv} falls back to {@code BigInteger} only on overflow,
 *       but the code had it private in {@code Strategist}. This class is now the single source.</li>
 *   <li>A {@code byte[]} overload of {@code parse} is added: MEXC's protobuf carries prices and
 *       quantities as ASCII digit strings inside the wire format (confirmed by hex-dumping real
 *       captured bytes — see {@code cf-arb-poc/cfarb/venues/mexc_pb.py}), so the hot path parses
 *       straight from the frame's byte buffer without ever allocating a {@code String}.</li>
 * </ol>
 *
 * Never on the hot path: {@link #toDouble}, {@link #fromDouble} — double conversion is for
 * analytics/JSON output only. Malformed input returns {@code Long.MIN_VALUE} (fail-closed sentinel,
 * hot-path rule R14) rather than throwing.
 */
public final class FixedPoint {

    public static final long SCALE = 100_000_000L;
    private static final long[] POW10 = {
            1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L, 100_000_000L
    };

    private FixedPoint() {
    }

    /** Parse cs[from, to) into a 1e8-scaled long. Returns Long.MIN_VALUE on malformed input. */
    public static long parse(CharSequence cs, int from, int to) {
        if (from >= to) return Long.MIN_VALUE;
        int i = from;
        boolean neg = false;
        char c = cs.charAt(i);
        if (c == '-') { neg = true; i++; }
        long intPart = 0;
        while (i < to) {
            c = cs.charAt(i);
            if (c == '.') break;
            int d = c - '0';
            if (d < 0 || d > 9) return Long.MIN_VALUE;
            intPart = intPart * 10 + d;
            i++;
        }
        long frac = 0;
        int fracDigits = 0;
        if (i < to && cs.charAt(i) == '.') {
            i++;
            while (i < to && fracDigits < 8) {
                int d = cs.charAt(i) - '0';
                if (d < 0 || d > 9) return Long.MIN_VALUE;
                frac = frac * 10 + d;
                fracDigits++;
                i++;
            }
            // truncate digits beyond 1e-8
        }
        long value = intPart * SCALE + frac * POW10[8 - fracDigits];
        return neg ? -value : value;
    }

    public static long parse(CharSequence cs) {
        return parse(cs, 0, cs.length());
    }

    /**
     * Byte-source overload for parsing straight out of a protobuf field's raw ASCII-digit bytes
     * (MEXC prices/quantities) without decoding to a {@code String} first. Identical digit-by-digit
     * semantics to the {@code CharSequence} overload.
     */
    public static long parse(byte[] buf, int from, int to) {
        if (from >= to) return Long.MIN_VALUE;
        int i = from;
        boolean neg = false;
        byte c = buf[i];
        if (c == '-') { neg = true; i++; }
        long intPart = 0;
        while (i < to) {
            c = buf[i];
            if (c == '.') break;
            int d = c - '0';
            if (d < 0 || d > 9) return Long.MIN_VALUE;
            intPart = intPart * 10 + d;
            i++;
        }
        long frac = 0;
        int fracDigits = 0;
        if (i < to && buf[i] == '.') {
            i++;
            while (i < to && fracDigits < 8) {
                int d = buf[i] - '0';
                if (d < 0 || d > 9) return Long.MIN_VALUE;
                frac = frac * 10 + d;
                fracDigits++;
                i++;
            }
        }
        long value = intPart * SCALE + frac * POW10[8 - fracDigits];
        return neg ? -value : value;
    }

    /** For analytics/JSON output only — never on the hot path. */
    public static double toDouble(long fixed) {
        return fixed / (double) SCALE;
    }

    public static long fromDouble(double v) {
        return Math.round(v * SCALE);
    }

    /**
     * {@code (a * b) / c} without overflow for our magnitude range, falling back to
     * {@code BigInteger} only when {@code a * b} would overflow a signed {@code long}.
     * Ported from {@code cf-trader.trader.Strategist} into its rightful home per rule R2 — and
     * FIXED while porting: the original overflow test was {@code Math.multiplyHigh(a, b) == 0},
     * which only proves the product fits in the UNSIGNED 64-bit range (up to ~1.8447e19). A
     * genuinely common case here — converting a few hundred dollars of notional (already
     * 1e8-fixed) by multiplying against {@link #SCALE} (1e8) to change units — routinely produces
     * products between Long.MAX_VALUE (~9.223e18) and 1.8447e19: multiplyHigh reports 0 (it fits
     * unsigned), but the plain {@code a * b} silently wraps into a NEGATIVE long because the
     * SIGNED 63-bit range was exceeded. Caught by {@code FixedPointTest} reproducing exactly this
     * shape (a $1,000 leg's budget-to-quantity conversion) — a real, not hypothetical, bug that
     * would have silently corrupted every VWAP ladder walk in {@link io.cfarb.strategy.Sizer}.
     *
     * <p>The correct test: no overflow iff the high word Math.multiplyHigh(a,b) is the sign
     * extension of the low word {@code a * b} (0 if non-negative, -1 if negative) — the standard
     * technique for detecting signed 64-bit multiplication overflow via the high-word intrinsic.
     */
    public static long mulDiv(long a, long b, long c) {
        long high = Math.multiplyHigh(a, b);
        long low = a * b;
        boolean overflow = high != (low >> 63);
        return !overflow
                ? low / c
                : java.math.BigInteger.valueOf(a).multiply(java.math.BigInteger.valueOf(b))
                        .divide(java.math.BigInteger.valueOf(c)).longValueExact();
    }

    /**
     * Quantize {@code qty} down to the nearest multiple of {@code step} (both 1e8-fixed-point),
     * i.e. {@code floor(qty / step) * step}. This is the exchange's own lot-size rounding
     * (cf-arb-bot-plan.md §5.3/§2.1) — MEXC fills a taker order at whatever quantity the venue's
     * matching engine accepts, never a finer-grained amount than {@code baseAssetPrecision}
     * allows. Returns 0 if {@code step <= 0} or {@code qty < step}.
     */
    public static long quantizeDown(long qty, long step) {
        if (step <= 0 || qty < step) return 0L;
        return (qty / step) * step;
    }

    /**
     * Render a 1e8-fixed value as a plain decimal string with exactly {@code decimals} fractional
     * digits (0-8), never scientific notation — MEXC's order endpoint rejects exponent notation
     * (e.g. {@code "1.0E-5"}) and expects the venue's own symbol precision. Trailing zeros beyond
     * {@code decimals} are truncated, not rounded (the value must already be quantized to a step at
     * or coarser than {@code decimals} — see {@link #quantizeDown}). Negative values are not
     * supported; every venue-bound quantity/price here is non-negative by construction.
     */
    public static String toPlainString(long fixed, int decimals) {
        if (fixed < 0) {
            throw new IllegalArgumentException("toPlainString does not support negative values: " + fixed);
        }
        if (decimals < 0 || decimals > 8) {
            throw new IllegalArgumentException("decimals must be 0-8, got " + decimals);
        }
        long intPart = fixed / SCALE;
        long fracPart = fixed % SCALE;
        // fracPart is the full 8-digit fraction; truncate (not round) to `decimals` digits.
        long divisor = POW10[8 - decimals];
        long truncatedFrac = fracPart / divisor;
        StringBuilder sb = new StringBuilder();
        sb.append(intPart);
        if (decimals > 0) {
            sb.append('.');
            String fracStr = Long.toString(truncatedFrac);
            for (int i = fracStr.length(); i < decimals; i++) {
                sb.append('0');
            }
            sb.append(fracStr);
        }
        return sb.toString();
    }
}

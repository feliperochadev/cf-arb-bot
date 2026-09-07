package io.cfarb.util;

/**
 * Ported verbatim from {@code recorder-service.util.ByteScan}. Byte-oriented "key":"value" / "key":123
 * scanning directly on raw frame bytes, no String allocation. Used by {@link io.cfarb.feed.MexcProtocol}
 * to classify control frames (subscription acks, rejections, PONG) that arrive as JSON even on an
 * otherwise-protobuf connection — never on true market-data (protobuf) frames.
 */
public final class ByteScan {

    private ByteScan() {
    }

    public static long stringValue(byte[] json, int from, int to, String key) {
        int idx = indexOfKey(json, from, to, key);
        if (idx < 0) return -1;
        int i = idx;
        while (i < to && json[i] != ':') i++;
        i++;
        while (i < to && json[i] <= ' ') i++;
        if (i >= to || json[i] != '"') return -1;
        int start = ++i;
        while (i < to && json[i] != '"') i++;
        if (i >= to) return -1;
        return ((long) start << 32) | i;
    }

    public static long longValue(byte[] json, int from, int to, String key) {
        int idx = indexOfKey(json, from, to, key);
        if (idx < 0) return Long.MIN_VALUE;
        int i = idx;
        while (i < to && json[i] != ':') i++;
        i++;
        while (i < to && json[i] <= ' ') i++;
        boolean neg = false;
        if (i < to && json[i] == '-') {
            neg = true;
            i++;
        }
        long v = 0;
        int digits = 0;
        while (i < to && json[i] >= '0' && json[i] <= '9') {
            v = v * 10 + (json[i] - '0');
            i++;
            digits++;
            if (digits > 18) return Long.MIN_VALUE;
        }
        if (digits == 0) return Long.MIN_VALUE;
        return neg ? -v : v;
    }

    /** True if {@code needle} (ASCII) occurs anywhere in {@code json[0,len)}. */
    public static boolean contains(byte[] json, int len, String needle) {
        int nlen = needle.length();
        if (nlen == 0 || nlen > len) return false;
        outer:
        for (int i = 0; i <= len - nlen; i++) {
            for (int j = 0; j < nlen; j++) {
                if (json[i + j] != (byte) needle.charAt(j)) continue outer;
            }
            return true;
        }
        return false;
    }

    public static int start(long packed) {
        return (int) (packed >>> 32);
    }

    public static int end(long packed) {
        return (int) packed;
    }

    private static int indexOfKey(byte[] json, int from, int to, String key) {
        int klen = key.length();
        int limit = to - klen - 2;
        outer:
        for (int i = Math.max(from, 0); i < limit; i++) {
            if (json[i] != '"') continue;
            for (int j = 0; j < klen; j++) {
                if (json[i + 1 + j] != (byte) key.charAt(j)) continue outer;
            }
            if (json[i + 1 + klen] == '"') {
                return i + 2 + klen;
            }
        }
        return -1;
    }
}

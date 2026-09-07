package io.cfarb.util;

/**
 * Ported verbatim from {@code cf-trader.util.JsonScan} (cf-arb-bot-plan.md §4: "still needed —
 * MEXC's control frames (PONG, subscription acks, 'Not Subscribed') are JSON even though market
 * data is protobuf"). Allocation-free {@code "key":"value"} extraction from small, well-known
 * control payloads; NOT a general JSON parser and never used on market-data frames (those go
 * through {@link ByteScan} + {@link io.cfarb.feed.ProtobufWalker}).
 */
public final class JsonScan {

    private JsonScan() {
    }

    /** Find the value of {@code "key":"..."} after {@code fromIndex}. Packed (start<<32)|end, or -1. */
    public static long stringValue(CharSequence json, String key, int fromIndex) {
        int idx = indexOfKey(json, key, fromIndex);
        if (idx < 0) return -1;
        int i = idx;
        int n = json.length();
        while (i < n && json.charAt(i) != ':') i++;
        i++;
        while (i < n && json.charAt(i) <= ' ') i++;
        if (i >= n || json.charAt(i) != '"') return -1;
        int start = ++i;
        while (i < n && json.charAt(i) != '"') i++;
        if (i >= n) return -1;
        return ((long) start << 32) | i;
    }

    public static int start(long packed) {
        return (int) (packed >>> 32);
    }

    public static int end(long packed) {
        return (int) packed;
    }

    private static int indexOfKey(CharSequence json, String key, int fromIndex) {
        int n = json.length();
        int klen = key.length();
        outer:
        for (int i = Math.max(fromIndex, 0); i < n - klen - 2; i++) {
            if (json.charAt(i) != '"') continue;
            for (int j = 0; j < klen; j++) {
                if (json.charAt(i + 1 + j) != key.charAt(j)) continue outer;
            }
            if (json.charAt(i + 1 + klen) == '"') {
                return i + 2 + klen;
            }
        }
        return -1;
    }

    /** True if the payload contains {@code "key":"value"} (exact string match). */
    public static boolean hasStringValue(CharSequence json, String key, String value) {
        long p = stringValue(json, key, 0);
        if (p == -1) return false;
        int s = start(p), e = end(p);
        if (e - s != value.length()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (json.charAt(s + i) != value.charAt(i)) return false;
        }
        return true;
    }
}

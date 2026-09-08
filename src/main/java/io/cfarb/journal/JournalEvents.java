package io.cfarb.journal;

import io.cfarb.util.EpochMicros;

/**
 * Builds NDJSON lines for {@link EventJournal}. Manual string building rather than a JSON library
 * — these events fire at single-digit-to-tens-per-hour rates (cf-arb-bot-plan.md §2's own
 * measurements), so allocation cost is irrelevant, but avoiding a Jackson dependency on this path
 * keeps the "rare branch" honest about being genuinely simple. Every field name here is also
 * documented in cf-arb-bot-plan.md §8's event-type list: {@code opportunity}, {@code cycle},
 * {@code broken_cycle}, {@code risk_trip}, {@code latency_snapshot}.
 *
 * <p>Every interpolated string field is escaped via {@link #esc} — cf-arb-bot-review-plan.md
 * Tier 2 step 2.6: {@code reason} strings carry exception messages and raw HTTP response bodies
 * (e.g. {@code exec.MexcRestClient.OrderRejectedException}), which can contain quotes/backslashes
 * and would otherwise produce malformed NDJSON that no downstream parser (Athena, DuckDB, jq) could
 * read.
 */
public final class JournalEvents {

    private JournalEvents() {
    }

    public static String opportunity(String triangleName, double netBps, long notionalFixed, boolean fired, String rejectReason) {
        return "{\"type\":\"opportunity\",\"ts_us\":" + EpochMicros.now()
                + ",\"triangle\":\"" + esc(triangleName) + "\""
                + ",\"net_bps\":" + jsonNumber(netBps)
                + ",\"notional_usd\":" + io.cfarb.util.FixedPoint.toDouble(notionalFixed)
                + ",\"fired\":" + fired
                + (rejectReason != null ? ",\"reject_reason\":\"" + esc(rejectReason) + "\"" : "")
                + "}";
    }

    public static String cycle(String triangleName, long notionalFixed, double detectedNetBps,
                                long realizedPnlFixed, long equityAfterFixed, long durationNanos) {
        return "{\"type\":\"cycle\",\"ts_us\":" + EpochMicros.now()
                + ",\"triangle\":\"" + esc(triangleName) + "\""
                + ",\"notional_usd\":" + io.cfarb.util.FixedPoint.toDouble(notionalFixed)
                + ",\"detected_net_bps\":" + jsonNumber(detectedNetBps)
                + ",\"realized_pnl_usd\":" + io.cfarb.util.FixedPoint.toDouble(realizedPnlFixed)
                + ",\"equity_after_usd\":" + io.cfarb.util.FixedPoint.toDouble(equityAfterFixed)
                + ",\"duration_ms\":" + (durationNanos / 1_000_000.0)
                + "}";
    }

    public static String brokenCycle(String triangleName, int failedLeg, String reason, long lossFixed, long equityAfterFixed) {
        return "{\"type\":\"broken_cycle\",\"ts_us\":" + EpochMicros.now()
                + ",\"triangle\":\"" + esc(triangleName) + "\""
                + ",\"failed_leg\":" + failedLeg
                + ",\"reason\":\"" + esc(reason) + "\""
                + ",\"loss_usd\":" + io.cfarb.util.FixedPoint.toDouble(lossFixed)
                + ",\"equity_after_usd\":" + io.cfarb.util.FixedPoint.toDouble(equityAfterFixed)
                + "}";
    }

    public static String riskTrip(String reason, long equityFixed) {
        return "{\"type\":\"risk_trip\",\"ts_us\":" + EpochMicros.now()
                + ",\"reason\":\"" + esc(reason) + "\""
                + ",\"equity_usd\":" + io.cfarb.util.FixedPoint.toDouble(equityFixed)
                + "}";
    }

    /** REVIEW.md MAJ-06: the feed watchdog forcing a reconnect (recoverable) rather than tripping
     * the kill switch outright -- {@code attempt} is the consecutive-detection count so a reader can
     * tell "reconnected on the first try" from "still stuck after several attempts, about to trip". */
    public static String feedReconnect(String reason, int attempt) {
        return "{\"type\":\"feed_reconnect\",\"ts_us\":" + EpochMicros.now()
                + ",\"reason\":\"" + esc(reason) + "\""
                + ",\"attempt\":" + attempt
                + "}";
    }

    public static String latencySnapshot(long p50Nanos, long p99Nanos, long maxNanos, String stage) {
        return "{\"type\":\"latency_snapshot\",\"ts_us\":" + EpochMicros.now()
                + ",\"stage\":\"" + esc(stage) + "\""
                + ",\"p50_us\":" + (p50Nanos / 1000.0)
                + ",\"p99_us\":" + (p99Nanos / 1000.0)
                + ",\"max_us\":" + (maxNanos / 1000.0)
                + "}";
    }

    /** NaN/Infinity are not valid JSON numbers -- render as JSON {@code null} rather than emitting
     * a token ("NaN") that breaks every downstream parser. */
    private static String jsonNumber(double v) {
        return Double.isFinite(v) ? Double.toString(v) : "null";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

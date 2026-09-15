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

    /** {@code net_bps} is the real achievable edge (VWAP-walked to size, lot-size quantized, fees) —
     * the value the fire decision is made on. {@code gross_bps} is the top-of-book cyclic edge
     * BEFORE depth/quantization ({@code EdgeCalculator.Result#grossBps}), i.e. the exact quantity
     * {@code cf-arb-poc}'s {@code stage2_cycles.evaluate_cycle} reports as its per-tick {@code net_bps}:
     * carrying both lets a dry-run journal be compared directly against that pipeline and separates
     * "no edge existed" from "edge existed but slippage/lot-size ate it". Either renders as JSON
     * {@code null} when {@code NaN} (e.g. an unfillable candidate has no {@code net_bps}). */
    public static String opportunity(String triangleName, double netBps, double grossBps, long notionalFixed,
                                      boolean fired, String rejectReason) {
        return opportunity(triangleName, netBps, grossBps, notionalFixed, fired, rejectReason, 0,
                null, null, null, null);
    }

    /**
     * Rich form (JOURNAL-TUNING-TASK.md T2 + T4).
     *
     * <p><b>T2 — {@code sampled_from}:</b> when {@code > 0}, the number of detector evaluations this
     * one line summarises. {@code OpportunityDetector} now keeps the BEST (highest {@code net_bps};
     * for {@code unfillable}, highest {@code gross_bps}) reject per {@code reject-sample-ms} window
     * instead of the first, so the tail — the only part that matters for tuning — stops being
     * invisible (JOURNAL-BPS-ANALYSIS.md §9.1: 97 % of evaluations were discarded as a uniform
     * sample).
     *
     * <p><b>T4 — per-leg depth:</b> when the four {@code leg*Fixed} arrays are non-null (each
     * length 3, index = leg 0..2) the event carries, per leg: top-of-book price, available quantity
     * AT the touch, the worst price the VWAP walk reached, and the quantized base quantity. Flat
     * arrays of plain numbers (not nested objects) to bound the line size. Lets a notebook compute
     * "largest notional that stays inside the touch" for every sample without re-running the bot
     * (JOURNAL-BPS-ANALYSIS.md §9.2). Only built on the sampled/fired path — never for a suppressed
     * candidate.
     */
    public static String opportunity(String triangleName, double netBps, double grossBps, long notionalFixed,
                                      boolean fired, String rejectReason, int sampledFrom,
                                      long[] legTopPxFixed, long[] legTouchQtyFixed,
                                      long[] legWorstPxFixed, long[] legBaseQtyFixed) {
        return opportunity(triangleName, netBps, grossBps, notionalFixed, fired, rejectReason, sampledFrom,
                legTopPxFixed, legTouchQtyFixed, legWorstPxFixed, legBaseQtyFixed, 0, Double.NaN);
    }

    /**
     * Richest form (DYNAMIC-SIZING-TASK.md Phase 2), adding the dynamic-sizing A/B measurement on
     * top of the T2/T4 form above: {@code size_candidates} (how many ladder-boundary sizes
     * {@code EdgeCalculator#evaluateBestSize} walked) and {@code net_bps_at_cap} (what the OLD
     * fixed-size path would have produced sizing at the operator's cap). Comparing {@code net_bps}
     * against {@code net_bps_at_cap} on the same line is what makes the "optimising dollars beats
     * optimising bps" claim (JOURNAL-BPS-ANALYSIS.md §14.1) verifiable from a live capture instead
     * of a two-point model fit. Both fields are omitted when {@code sizeCandidates <= 0} — a bailed
     * candidate, or one produced by the plain fixed-size {@code evaluate}, never ran the search.
     */
    public static String opportunity(String triangleName, double netBps, double grossBps, long notionalFixed,
                                      boolean fired, String rejectReason, int sampledFrom,
                                      long[] legTopPxFixed, long[] legTouchQtyFixed,
                                      long[] legWorstPxFixed, long[] legBaseQtyFixed,
                                      int sizeCandidates, double netBpsAtCap) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"type\":\"opportunity\",\"ts_us\":").append(EpochMicros.now())
                .append(",\"triangle\":\"").append(esc(triangleName)).append('"')
                .append(",\"net_bps\":").append(jsonNumber(netBps))
                .append(",\"gross_bps\":").append(jsonNumber(grossBps))
                .append(",\"notional_usd\":").append(io.cfarb.util.FixedPoint.toDouble(notionalFixed))
                .append(",\"fired\":").append(fired);
        if (rejectReason != null) {
            sb.append(",\"reject_reason\":\"").append(esc(rejectReason)).append('"');
        }
        if (sampledFrom > 0) {
            sb.append(",\"sampled_from\":").append(sampledFrom);
        }
        if (legTopPxFixed != null && legTouchQtyFixed != null
                && legWorstPxFixed != null && legBaseQtyFixed != null) {
            sb.append(",\"leg_top_px\":").append(jsonArr3(legTopPxFixed));
            sb.append(",\"leg_touch_qty\":").append(jsonArr3(legTouchQtyFixed));
            sb.append(",\"leg_worst_px\":").append(jsonArr3(legWorstPxFixed));
            sb.append(",\"leg_base_qty\":").append(jsonArr3(legBaseQtyFixed));
        }
        if (sizeCandidates > 0) {
            sb.append(",\"size_candidates\":").append(sizeCandidates);
            sb.append(",\"net_bps_at_cap\":").append(jsonNumber(netBpsAtCap));
        }
        return sb.append('}').toString();
    }

    /** JOURNAL-TUNING-TASK.md T1c: a crossed L2 book was force-reset by the self-heal path. */
    public static String bookReset(String symbol, String reason, long topBidFixed, long topAskFixed,
                                    long updateCount, double crossedForMs) {
        return "{\"type\":\"book_reset\",\"ts_us\":" + EpochMicros.now()
                + ",\"symbol\":\"" + esc(symbol) + "\""
                + ",\"reason\":\"" + esc(reason) + "\""
                + ",\"top_bid\":" + io.cfarb.util.FixedPoint.toDouble(topBidFixed)
                + ",\"top_ask\":" + io.cfarb.util.FixedPoint.toDouble(topAskFixed)
                + ",\"update_count\":" + updateCount
                + ",\"crossed_for_ms\":" + crossedForMs
                + "}";
    }

    private static String jsonArr3(long[] a) {
        return "[" + io.cfarb.util.FixedPoint.toDouble(a[0]) + ","
                + io.cfarb.util.FixedPoint.toDouble(a[1]) + ","
                + io.cfarb.util.FixedPoint.toDouble(a[2]) + "]";
    }

    public static String cycle(String triangleName, long notionalFixed, double detectedNetBps,
                                long realizedPnlFixed, long equityAfterFixed, long durationNanos) {
        return cycle(triangleName, notionalFixed, detectedNetBps, realizedPnlFixed, equityAfterFixed,
                durationNanos, null, null);
    }

    /**
     * PRE-LIVE-PLAN.md P1-5: adds {@code leg_worst_px} (the MODELLED price {@code EdgeCalculator}
     * detected, {@code OrderIntent#legWorstPriceFixed()}) alongside the new {@code leg_requested_px}
     * (what {@code CycleExecutor} actually submitted after {@code cf-bot.exec.leg-cross-bps}'s
     * buffer) — carrying both on the SAME line makes the per-leg gap measurable directly, without
     * correlating back to a separate {@code opportunity} line by timestamp/triangle. Omitted (both
     * null) for the dry-run path, which never applies a buffer.
     */
    public static String cycle(String triangleName, long notionalFixed, double detectedNetBps,
                                long realizedPnlFixed, long equityAfterFixed, long durationNanos,
                                long[] legWorstPxFixed, long[] legRequestedPxFixed) {
        StringBuilder sb = new StringBuilder(224);
        sb.append("{\"type\":\"cycle\",\"ts_us\":").append(EpochMicros.now())
                .append(",\"triangle\":\"").append(esc(triangleName)).append('"')
                .append(",\"notional_usd\":").append(io.cfarb.util.FixedPoint.toDouble(notionalFixed))
                .append(",\"detected_net_bps\":").append(jsonNumber(detectedNetBps))
                .append(",\"realized_pnl_usd\":").append(io.cfarb.util.FixedPoint.toDouble(realizedPnlFixed))
                .append(",\"equity_after_usd\":").append(io.cfarb.util.FixedPoint.toDouble(equityAfterFixed))
                .append(",\"duration_ms\":").append(durationNanos / 1_000_000.0);
        if (legWorstPxFixed != null && legRequestedPxFixed != null) {
            sb.append(",\"leg_worst_px\":").append(jsonArr3(legWorstPxFixed));
            sb.append(",\"leg_requested_px\":").append(jsonArr3(legRequestedPxFixed));
        }
        return sb.append('}').toString();
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

    /** REVIEW.md MED-10, dedicated event added by the third-pass review: a stale {@code OrderIntent}
     * dropped without ever being submitted. Previously reused {@code brokenCycle}'s shape with
     * {@code failed_leg=-1} as a sentinel meaning "no leg was ever attempted" -- but nothing else
     * that shape's consumers (dashboards, the {@code cfarb.cycles.broken} metric) treat -1 as
     * special, so a stale-intent drop silently inflated the broken-cycle line count in the NDJSON
     * while {@code metrics.recordCycleBroken()} was deliberately never called for it, leaving the
     * journal and the Prometheus counter permanently disagreeing about how many cycles actually
     * broke. */
    public static String intentExpired(String triangleName, long ageNanos, long equityFixed) {
        return "{\"type\":\"intent_expired\",\"ts_us\":" + EpochMicros.now()
                + ",\"triangle\":\"" + esc(triangleName) + "\""
                + ",\"age_ms\":" + (ageNanos / 1_000_000.0)
                + ",\"equity_usd\":" + io.cfarb.util.FixedPoint.toDouble(equityFixed)
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

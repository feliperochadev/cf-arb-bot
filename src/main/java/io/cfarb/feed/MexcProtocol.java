package io.cfarb.feed;

import io.cfarb.util.ByteScan;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * MEXC spot public depth stream subscription + control-frame classification. Ported from
 * {@code recorder-service.capture.protocol.MexcProtocol} (cf-arb-bot-plan.md §4: "the javadoc there
 * encodes a 10-minute outage's worth of hard-won knowledge").
 *
 * <p><b>Channel-naming rule — probe-verified, and MEXC's docs are wrong about it.</b> MEXC serves
 * public clients only the {@code spot@public.aggre.*} family (plus {@code limit.depth}). The
 * {@code increase.*} family and the bare {@code bookTicker} channel are refused with
 * {@code "Not Subscribed successfully! ... Blocked!"} even though MEXC's own published docs still
 * list {@code increase.depth} as current — shipping it cost recorder-service a 10-minute run with
 * 18 reconnects and zero market data. Never add a channel here without probing it live first.
 *
 * <p><b>Channel choice for THIS bot (differs from recorder-service's default):</b> Gate 0 step 4
 * live-probed {@code aggre.depth@100ms}, {@code aggre.depth@10ms}, and {@code limit.depth@20} for
 * 30s each. {@code limit.depth@20} — the plan's original guess at a fast snapshot channel — turned
 * out to be a 500ms-cadence channel, SLOWER than even {@code @100ms}. {@code aggre.depth@10ms} won
 * clearly: real per-symbol push intervals of 14-41ms, well under the ~200ms median opportunity
 * window (cf-arb-bot-plan.md §3 step 4, §5.2). {@code cf-bot.venue.depth-channel} defaults to it.
 */
public final class MexcProtocol {

    public static final int MAX_STREAMS_PER_CONNECTION = 30;
    public static final long KEEPALIVE_INTERVAL_MS = 20_000;
    public static final String KEEPALIVE_MESSAGE = "{\"method\":\"PING\"}";

    private MexcProtocol() {
    }

    /**
     * Build batched {@code SUBSCRIPTION} messages for the configured depth channel spec
     * (e.g. {@code "aggre.depth@10ms"} or {@code "limit.depth@20"}) across {@code symbols},
     * honoring the 30-streams-per-connection cap.
     */
    public static List<String> subscribeMessages(String depthChannelSpec, List<String> symbols) {
        String template = toWireTemplate(depthChannelSpec);
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i += MAX_STREAMS_PER_CONNECTION) {
            List<String> batch = symbols.subList(i, Math.min(i + MAX_STREAMS_PER_CONNECTION, symbols.size()));
            StringJoiner params = new StringJoiner(",");
            for (String s : batch) {
                params.add("\"" + String.format(template, s) + "\"");
            }
            messages.add("{\"method\":\"SUBSCRIPTION\",\"params\":[" + params + "]}");
        }
        return messages;
    }

    /** "aggre.depth@10ms" -> "spot@public.aggre.depth.v3.api.pb@10ms@%s";
     *  "limit.depth@20"   -> "spot@public.limit.depth.v3.api.pb@%s@20". */
    static String toWireTemplate(String spec) {
        String[] parts = spec.split("@", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("cf-bot.venue.depth-channel must be '<kind>@<param>', got: " + spec);
        }
        String kind = parts[0];
        String param = parts[1];
        return switch (kind) {
            case "aggre.depth" -> "spot@public.aggre.depth.v3.api.pb@" + param + "@%s";
            case "limit.depth" -> "spot@public.limit.depth.v3.api.pb@%s@" + param;
            default -> throw new IllegalArgumentException("unsupported MEXC depth channel kind: " + kind);
        };
    }

    public enum FrameClass {
        MARKET_DATA, SUBSCRIPTION_REJECTED, KEEPALIVE_REPLY, CONTROL_ACK
    }

    /** Classify a raw WS frame's bytes. Market data is protobuf and contains none of these ASCII
     * markers; control responses are small JSON text frames — mirrors
     * {@code recorder-service.capture.protocol.MexcProtocol#classifyFrame} exactly. */
    public static FrameClass classifyFrame(byte[] payload, int len) {
        if (ByteScan.contains(payload, len, "Not Subscribed")) {
            return FrameClass.SUBSCRIPTION_REJECTED;
        }
        if (ByteScan.contains(payload, len, "\"PONG\"")) {
            return FrameClass.KEEPALIVE_REPLY;
        }
        if (ByteScan.contains(payload, len, "\"code\":0") && ByteScan.contains(payload, len, "\"msg\":\"spot@")) {
            return FrameClass.CONTROL_ACK;
        }
        return FrameClass.MARKET_DATA;
    }
}

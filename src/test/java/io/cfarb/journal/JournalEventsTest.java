package io.cfarb.journal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cfarb.util.FixedPoint;
import org.junit.jupiter.api.Test;

/**
 * cf-arb-bot-review-plan.md Tier 2 step 2.6: reason strings carry raw exception messages and HTTP
 * response bodies (e.g. {@code MexcRestClient.OrderRejectedException}'s body), which routinely
 * contain quotes and backslashes -- every emitted line must still parse as valid JSON.
 */
class JournalEventsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void brokenCycleReasonWithQuotesAndBackslashesStillParsesAsJson() {
        // Exactly the shape a real MEXC error body produces: OrderRejectedException's message plus
        // the raw response body, both string-concatenated into `reason` upstream.
        String reason = "order-error: io.cfarb.exec.MexcRestClient$OrderRejectedException: "
                + "MEXC order rejected: HTTP 400 {\"code\":-1013,\"msg\":\"Filter failure: "
                + "LOT_SIZE\",\"path\":\"C:\\\\Users\\\\trader\\\\order.json\"}";
        String line = JournalEvents.brokenCycle("usdt-btc-xrp-fwd", 1, reason, -50_000_000L, 5_000_000_000L);

        JsonNode node = assertDoesNotThrow(() -> MAPPER.readTree(line), "must be valid JSON: " + line);
        assertEquals(reason, node.get("reason").asText(), "escaping must round-trip losslessly");
        assertEquals("broken_cycle", node.get("type").asText());
    }

    @Test
    void opportunityRejectReasonWithNewlinesAndControlCharsStillParsesAsJson() {
        String reason = "unfillable\nsecond line\twith a tab and a \" quote";
        String line = JournalEvents.opportunity("test-triangle", 3.5, 12.0, 100_000_000L, false, reason);

        JsonNode node = assertDoesNotThrow(() -> MAPPER.readTree(line));
        assertEquals(reason, node.get("reject_reason").asText());
        assertEquals(3.5, node.get("net_bps").asDouble());
        assertEquals(12.0, node.get("gross_bps").asDouble());
    }

    @Test
    void nanNetBpsRendersAsJsonNullNotAnInvalidToken() {
        // Double.NaN.toString() is "NaN", which is not valid JSON and would break every downstream
        // parser (Athena, DuckDB, jq) reading this line. An unfillable candidate has no net_bps but
        // DOES carry a gross_bps (top-of-book edge) -- both fields are guarded the same way.
        String line = JournalEvents.opportunity("test-triangle", Double.NaN, 8.25, 100_000_000L, false, "unfillable");

        JsonNode node = assertDoesNotThrow(() -> MAPPER.readTree(line));
        assertEquals(true, node.get("net_bps").isNull());
        assertEquals(8.25, node.get("gross_bps").asDouble());

        // both NaN (books not usable) -> both null, still valid JSON
        JsonNode bothNaN = assertDoesNotThrow(() -> MAPPER.readTree(
                JournalEvents.opportunity("test-triangle", Double.NaN, Double.NaN, 100_000_000L, false, "unfillable")));
        assertEquals(true, bothNaN.get("gross_bps").isNull());
    }

    @Test
    void intentExpiredIsItsOwnEventTypeNotBrokenCycleWithASentinelLeg() {
        // Third-pass review finding: a stale-intent drop is not a broken cycle (no leg was ever
        // attempted) -- it gets its own event type so cfarb.cycles.broken and the NDJSON line count
        // stay consistent, and so the field name for "how old" is self-documenting rather than a
        // -1 magic value in a field named failed_leg.
        String line = JournalEvents.intentExpired("usdt-btc-xrp-fwd", 200_000_000L, 5_000_000_000L);

        JsonNode node = assertDoesNotThrow(() -> MAPPER.readTree(line));
        assertEquals("intent_expired", node.get("type").asText());
        assertEquals(200.0, node.get("age_ms").asDouble());
        assertEquals("usdt-btc-xrp-fwd", node.get("triangle").asText());
    }

    @Test
    void richOpportunityCarriesSampledFromAndFlatPerLegDepthArrays() {
        // JOURNAL-TUNING-TASK.md T2 + T4.
        long[] topPx = {FixedPoint.fromDouble(120000.0), FixedPoint.fromDouble(0.0001), FixedPoint.fromDouble(1.38)};
        long[] touchQty = {FixedPoint.fromDouble(0.5), FixedPoint.fromDouble(200.0), FixedPoint.fromDouble(1500.0)};
        long[] worstPx = {FixedPoint.fromDouble(120001.0), FixedPoint.fromDouble(0.000101), FixedPoint.fromDouble(1.379)};
        long[] baseQty = {FixedPoint.fromDouble(0.008), FixedPoint.fromDouble(180.0), FixedPoint.fromDouble(180.0)};

        String line = JournalEvents.opportunity("usdt-eth-xrp-fwd", -2.5, 1.75, 100_000_000L, false,
                "below-threshold", 47, topPx, touchQty, worstPx, baseQty);
        JsonNode n = assertDoesNotThrow(() -> MAPPER.readTree(line));

        assertEquals(47, n.get("sampled_from").asInt());
        assertEquals("below-threshold", n.get("reject_reason").asText());
        assertEquals(3, n.get("leg_top_px").size());
        assertEquals(120000.0, n.get("leg_top_px").get(0).asDouble(), 1e-6);
        assertEquals(200.0, n.get("leg_touch_qty").get(1).asDouble(), 1e-6);
        assertEquals(1.379, n.get("leg_worst_px").get(2).asDouble(), 1e-6);
        assertEquals(180.0, n.get("leg_base_qty").get(2).asDouble(), 1e-6);
    }

    @Test
    void plainOpportunityOmitsSampledFromAndLegArrays() {
        String line = JournalEvents.opportunity("t", 7.5, 9.0, 100_000_000L, true, null);
        JsonNode n = assertDoesNotThrow(() -> MAPPER.readTree(line));
        assertEquals(false, n.has("sampled_from"));
        assertEquals(false, n.has("leg_top_px"));
        assertEquals(true, n.get("fired").asBoolean());
    }

    @Test
    void bookResetEventParsesAndNamesTheSymbolAndReason() {
        String line = JournalEvents.bookReset("BTCUSDT", "crossed-latch",
                FixedPoint.fromDouble(120431.55), FixedPoint.fromDouble(120429.10), 481203L, 500.0);
        JsonNode n = assertDoesNotThrow(() -> MAPPER.readTree(line));
        assertEquals("book_reset", n.get("type").asText());
        assertEquals("BTCUSDT", n.get("symbol").asText());
        assertEquals("crossed-latch", n.get("reason").asText());
        assertEquals(481203L, n.get("update_count").asLong());
        assertEquals(500.0, n.get("crossed_for_ms").asDouble());
    }

    @Test
    void riskTripReasonWithEmbeddedJsonLikeTextStillParsesAsJson() {
        // Exactly the kind of reason string executor-exception failures produce.
        String reason = "executor-exception: java.lang.RuntimeException: response was {\"status\":\"error\"}";
        String line = JournalEvents.riskTrip(reason, 4_000_000_000L);

        JsonNode node = assertDoesNotThrow(() -> MAPPER.readTree(line));
        assertEquals(reason, node.get("reason").asText());
        assertEquals("risk_trip", node.get("type").asText());
    }

    // --- PRE-LIVE-PLAN.md P1-5: cycle() gains leg_worst_px / leg_requested_px --------------------

    @Test
    void sixArgCycleOmitsLegPriceArraysEntirely() {
        String line = JournalEvents.cycle("usdt-btc-xrp-fwd", FixedPoint.fromDouble(1_000.0), 5.0,
                FixedPoint.fromDouble(2.5), FixedPoint.fromDouble(2_502.5), 12_000_000L);

        JsonNode node = assertDoesNotThrow(() -> MAPPER.readTree(line));
        assertEquals("cycle", node.get("type").asText());
        assertEquals(false, node.has("leg_worst_px"), "the dry-run path never applies a buffer -- must be omitted");
        assertEquals(false, node.has("leg_requested_px"));
    }

    @Test
    void eightArgCycleCarriesBothModelledAndRequestedPricesPerLeg() {
        long[] worst = {FixedPoint.fromDouble(77850.0), FixedPoint.fromDouble(0.000017), FixedPoint.fromDouble(1.40)};
        long[] requested = {FixedPoint.fromDouble(77857.79), FixedPoint.fromDouble(0.0000170017),
                FixedPoint.fromDouble(1.3999)};
        String line = JournalEvents.cycle("usdt-btc-xrp-fwd", FixedPoint.fromDouble(1_000.0), 5.0,
                FixedPoint.fromDouble(2.5), FixedPoint.fromDouble(2_502.5), 12_000_000L, worst, requested);

        JsonNode node = assertDoesNotThrow(() -> MAPPER.readTree(line));
        assertEquals(3, node.get("leg_worst_px").size());
        assertEquals(3, node.get("leg_requested_px").size());
        assertEquals(77850.0, node.get("leg_worst_px").get(0).asDouble(), 0.01);
        assertEquals(77857.79, node.get("leg_requested_px").get(0).asDouble(), 0.01,
                "the gap between modelled and requested price must be measurable directly off this one line");
    }
}

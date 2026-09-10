package io.cfarb.journal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void riskTripReasonWithEmbeddedJsonLikeTextStillParsesAsJson() {
        // Exactly the kind of reason string executor-exception failures produce.
        String reason = "executor-exception: java.lang.RuntimeException: response was {\"status\":\"error\"}";
        String line = JournalEvents.riskTrip(reason, 4_000_000_000L);

        JsonNode node = assertDoesNotThrow(() -> MAPPER.readTree(line));
        assertEquals(reason, node.get("reason").asText());
        assertEquals("risk_trip", node.get("type").asText());
    }
}

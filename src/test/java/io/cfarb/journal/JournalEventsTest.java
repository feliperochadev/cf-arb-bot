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
        String line = JournalEvents.opportunity("test-triangle", 3.5, 100_000_000L, false, reason);

        JsonNode node = assertDoesNotThrow(() -> MAPPER.readTree(line));
        assertEquals(reason, node.get("reject_reason").asText());
    }

    @Test
    void nanNetBpsRendersAsJsonNullNotAnInvalidToken() {
        // Double.NaN.toString() is "NaN", which is not valid JSON and would break every downstream
        // parser (Athena, DuckDB, jq) reading this line.
        String line = JournalEvents.opportunity("test-triangle", Double.NaN, 100_000_000L, false, "unfillable");

        JsonNode node = assertDoesNotThrow(() -> MAPPER.readTree(line));
        assertEquals(true, node.get("net_bps").isNull());
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

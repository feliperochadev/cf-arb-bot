package io.cfarb.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.book.BookRegistry;
import io.cfarb.config.BotConfig;
import io.cfarb.model.SymbolFilter;
import io.cfarb.util.FixedPoint;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * cf-arb-bot-review-plan.md new defect 2: nothing previously checked that a configured triangle's
 * legs actually chain together (leg i's toAsset must equal leg i+1's fromAsset) or that it starts
 * and ends at the configured anchor asset. A typo in application.properties would otherwise silently
 * produce a "triangle" whose net_bps compares mismatched currencies.
 */
class TriangleRegistryTest {

    private static SymbolFilter filter(String symbol, String base, String quote) {
        return new SymbolFilter(symbol, base, quote, FixedPoint.fromDouble(0.01), 2,
                FixedPoint.fromDouble(0.01), FixedPoint.fromDouble(1.0), 2, 5.0,
                FixedPoint.fromDouble(0.9995), Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.05, 0.05);
    }

    private static BotConfig.TriangleConfig triangleConfig(String... legs) {
        return triangleConfig(java.util.OptionalDouble.empty(), legs);
    }

    private static BotConfig.TriangleConfig triangleConfig(java.util.OptionalDouble maxNotionalUsd, String... legs) {
        return new BotConfig.TriangleConfig() {
            public boolean enabled() { return true; }
            public List<String> legs() { return List.of(legs); }
            public java.util.OptionalDouble maxNotionalUsd() { return maxNotionalUsd; }
        };
    }

    private static final String[] VALID_LEGS = {"BTCUSDT:ASK", "XRPBTC:ASK", "XRPUSDT:BID"};

    private static final Map<String, SymbolFilter> FILTERS = Map.of(
            "BTCUSDT", filter("BTCUSDT", "BTC", "USDT"),
            "XRPBTC", filter("XRPBTC", "XRP", "BTC"),
            "XRPUSDT", filter("XRPUSDT", "XRP", "USDT"),
            "XRPUSDC", filter("XRPUSDC", "XRP", "USDC"));

    private static final BookRegistry BOOKS =
            new BookRegistry(List.of("BTCUSDT", "XRPBTC", "XRPUSDT", "XRPUSDC"), 1, 0);

    @Test
    void acceptsAValidClosedTriangleStartingAndEndingAtTheAnchor() {
        Map<String, BotConfig.TriangleConfig> triangles = Map.of("usdt-btc-xrp-fwd",
                triangleConfig("BTCUSDT:ASK", "XRPBTC:ASK", "XRPUSDT:BID"));

        TriangleRegistry registry = new TriangleRegistry(triangles, "USDT", BOOKS, FILTERS);

        assertEquals(1, registry.triangleCount());
        Triangle t = registry.triangle(0);
        assertEquals("USDT", t.fromAsset()[0]);
        assertEquals("USDT", t.toAsset()[2]);
    }

    // --- JOURNAL-TUNING-TASK.md T5: per-triangle notional cap ---

    @Test
    void absentPerTriangleCapInheritsTheGlobalCap() {
        Map<String, BotConfig.TriangleConfig> triangles = Map.of("t", triangleConfig(VALID_LEGS));
        TriangleRegistry r = new TriangleRegistry(triangles, "USDT", BOOKS, FILTERS, 5_000.0);
        assertEquals(FixedPoint.fromDouble(5_000.0), r.maxNotionalFixed(0));
    }

    @Test
    void aPresentPerTriangleCapNarrowsTheGlobalCap() {
        Map<String, BotConfig.TriangleConfig> triangles = Map.of("t",
                triangleConfig(java.util.OptionalDouble.of(200.0), VALID_LEGS));
        TriangleRegistry r = new TriangleRegistry(triangles, "USDT", BOOKS, FILTERS, 5_000.0);
        assertEquals(FixedPoint.fromDouble(200.0), r.maxNotionalFixed(0));
    }

    @Test
    void aNonPositivePerTriangleCapFailsTheBoot() {
        Map<String, BotConfig.TriangleConfig> triangles = Map.of("t",
                triangleConfig(java.util.OptionalDouble.of(0.0), VALID_LEGS));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new TriangleRegistry(triangles, "USDT", BOOKS, FILTERS, 5_000.0));
        assertTrue(ex.getMessage().contains("must be > 0"));
    }

    @Test
    void aPerTriangleCapAboveTheGlobalCapFailsTheBoot() {
        Map<String, BotConfig.TriangleConfig> triangles = Map.of("t",
                triangleConfig(java.util.OptionalDouble.of(9_999.0), VALID_LEGS));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new TriangleRegistry(triangles, "USDT", BOOKS, FILTERS, 5_000.0));
        assertTrue(ex.getMessage().contains("may only NARROW"));
    }

    @Test
    void rejectsALegChainThatDoesNotConnect() {
        // A typo: leg[1] should spend XRP (leg[0]'s output, BTC... wait) -- here leg[0] produces BTC
        // but leg[1] (XRPUSDC) spends USDC, not BTC. The chain is broken.
        Map<String, BotConfig.TriangleConfig> triangles = Map.of("broken",
                triangleConfig("BTCUSDT:ASK", "XRPUSDC:ASK", "XRPUSDT:BID"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new TriangleRegistry(triangles, "USDT", BOOKS, FILTERS));
        assertEquals(true, ex.getMessage().contains("does not chain"));
    }

    @Test
    void rejectsALegZeroThatDoesNotSpendTheAnchor() {
        // leg[0] (XRPBTC:ASK) spends BTC, not the configured anchor USDT.
        Map<String, BotConfig.TriangleConfig> triangles = Map.of("wrong-start",
                triangleConfig("XRPBTC:ASK", "XRPUSDT:BID", "BTCUSDT:ASK"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new TriangleRegistry(triangles, "USDT", BOOKS, FILTERS));
        assertEquals(true, ex.getMessage().contains("must spend the anchor"));
    }

    @Test
    void rejectsALegTwoThatDoesNotReturnToTheAnchor() {
        // leg[2] (XRPBTC:BID) returns BTC, not USDT -- never gets back to the anchor.
        Map<String, BotConfig.TriangleConfig> triangles = Map.of("wrong-end",
                triangleConfig("BTCUSDT:ASK", "XRPUSDT:BID", "XRPBTC:BID"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new TriangleRegistry(triangles, "USDT", BOOKS, FILTERS));
        assertEquals(true, ex.getMessage().contains("must return to the anchor"));
    }
}

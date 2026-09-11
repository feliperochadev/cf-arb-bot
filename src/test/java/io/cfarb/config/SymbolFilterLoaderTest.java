package io.cfarb.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.model.SymbolFilter;
import io.cfarb.util.FixedPoint;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * JOURNAL-TUNING-TASK.md T9: {@code cf-bot.fees.taker-discount-pct} scales every symbol's
 * {@code taker_bps} at load, so the operator does not hand-edit {@code mexc_filters.json} when they
 * start holding MX.
 */
class SymbolFilterLoaderTest {

    private static Map<String, SymbolFilter> load(double discountPct) throws Exception {
        try (InputStream in = SymbolFilterLoaderTest.class.getResourceAsStream("/config/mexc_filters.json")) {
            return SymbolFilterLoader.load(in, "classpath:test", discountPct);
        }
    }

    @Test
    void zeroDiscountLeavesTheShippedNumbersUntouched() throws Exception {
        Map<String, SymbolFilter> f = load(0.0);
        assertEquals(5.0, f.get("BTCUSDT").takerBps(), 1e-9);
        assertEquals(0.0, f.get("USDCUSDT").takerBps(), 1e-9);
    }

    @Test
    void fiftyPercentDiscountHalvesTheStandardTakerLegsAndLeavesZeroFeePairsAtZero() throws Exception {
        Map<String, SymbolFilter> f = load(50.0);

        assertEquals(2.5, f.get("BTCUSDT").takerBps(), 1e-9);
        assertEquals(2.5, f.get("ETHUSDT").takerBps(), 1e-9);
        // (1 - 2.5/10_000) as 1e8-fixed -- what Sizer multiplies by on every ladder walk.
        assertEquals(FixedPoint.fromDouble(1.0 - 2.5 / 10_000.0),
                f.get("BTCUSDT").takerFeeMultiplierFixed());

        // USDC/USD1 promotional zero-fee pairs: 0 * anything = 0, unaffected.
        assertEquals(0.0, f.get("USDCUSDT").takerBps(), 1e-9);
        assertEquals(0.0, f.get("XRPUSDT").takerBps(), 1e-9);
        assertEquals(FixedPoint.fromDouble(1.0), f.get("USDCUSDT").takerFeeMultiplierFixed());
    }

    @Test
    void twentyPercentDiscountIsAlsoSupported() throws Exception {
        Map<String, SymbolFilter> f = load(20.0);
        assertEquals(4.0, f.get("BTCUSDT").takerBps(), 1e-9);
    }

    @Test
    void everyConfiguredSymbolStillLoads() throws Exception {
        Map<String, SymbolFilter> f = load(50.0);
        assertTrue(f.size() >= 25, "expected the full 30-symbol universe, got " + f.size());
    }
}

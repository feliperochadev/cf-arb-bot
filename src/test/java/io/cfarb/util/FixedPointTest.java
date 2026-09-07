package io.cfarb.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FixedPointTest {

    @Test
    void parsesIntegerAndDecimalPrices() {
        assertEquals(10000000000L, FixedPoint.parse("100.00"));
        assertEquals(150500000L, FixedPoint.parse("1.505"));
        assertEquals(1L, FixedPoint.parse("0.00000001"));
    }

    @Test
    void truncatesBeyondEightDecimals() {
        // MEXC/exchange precision never exceeds 8 decimals for the pairs we trade, but a
        // pathological upstream value must not corrupt parsing -- extra digits are truncated.
        assertEquals(FixedPoint.parse("1.12345678"), FixedPoint.parse("1.123456789"));
    }

    @Test
    void rejectsMalformedInput() {
        assertEquals(Long.MIN_VALUE, FixedPoint.parse("abc"));
        assertEquals(Long.MIN_VALUE, FixedPoint.parse(""));
        assertEquals(Long.MIN_VALUE, FixedPoint.parse("1.2x"));
    }

    @Test
    void byteOverloadMatchesCharSequenceOverload() {
        String[] samples = {"100.00", "0.00000001", "-1.5", "78865.54", "1.383"};
        for (String s : samples) {
            long fromChars = FixedPoint.parse(s);
            byte[] buf = s.getBytes(StandardCharsets.US_ASCII);
            long fromBytes = FixedPoint.parse(buf, 0, buf.length);
            assertEquals(fromChars, fromBytes, "mismatch for " + s);
        }
    }

    @Test
    void mulDivHandlesTheCommonCaseAndTheOverflowFallback() {
        // common case: no overflow
        assertEquals(200L, FixedPoint.mulDiv(100L, 4L, 2L));
        // large magnitudes that overflow a plain (a*b) but not the BigInteger fallback
        long a = 92_000_000_000_000_000L; // ~9.2e16, near Long.MAX_VALUE / 100
        long b = 100L;
        long c = 100L;
        assertEquals(a, FixedPoint.mulDiv(a, b, c));
    }

    @Test
    void quantizeDownFloorsToLotStep() {
        long step = FixedPoint.fromDouble(0.01); // MEXC SOLUSDC-style step
        long qty = FixedPoint.fromDouble(1.0349999); // slightly under 1.035
        long expected = FixedPoint.fromDouble(1.03);
        assertEquals(expected, FixedPoint.quantizeDown(qty, step), 2);
    }

    @Test
    void quantizeDownBelowStepReturnsZero() {
        long step = FixedPoint.fromDouble(1.0); // e.g. SOLBTC's 1-whole-SOL minimum
        long qty = FixedPoint.fromDouble(0.99); // a $100 seed's worth of SOL, roughly
        assertEquals(0L, FixedPoint.quantizeDown(qty, step));
    }

    @Test
    void mulDivDoesNotSilentlyOverflowInTheSignedRangeBetweenLongMaxAndTwoToTheSixtyFour() {
        // Regression for a real bug found while porting cf-trader's mulDiv: Math.multiplyHigh(a,b)
        // == 0 only proves the product fits UNSIGNED in 64 bits (~1.8447e19), not that it fits the
        // SIGNED 63-bit range (~9.223e18) that `a * b` actually computes into. This exact shape —
        // a few-hundred-dollar budget (1e8-fixed) times SCALE (1e8) to convert units, as
        // Sizer.fillLeg does on every leg -- lands squarely between those two bounds and used to
        // come back negative.
        long budget = FixedPoint.fromDouble(1000.0); // 1e11
        long price = FixedPoint.fromDouble(2450.0);  // 2.45e11
        long result = FixedPoint.mulDiv(budget, FixedPoint.SCALE, price);
        assertEquals(0.4081632653, FixedPoint.toDouble(result), 1e-6);
        assertTrue(result > 0, "must not silently wrap negative");
    }

    // cf-arb-bot-review-plan.md Tier 1 step 1.2: exec.CycleExecutor used to render order params via
    // `double`, which can emit scientific notation (e.g. "1.0E-5") that MEXC's order endpoint
    // rejects. toPlainString must never do that, at any symbol precision this bot trades.

    @Test
    void toPlainStringNeverEmitsScientificNotationAtSmallMagnitudes() {
        // A BTC quantity like 0.00001 is exactly the shape that `Double.toString` renders as "1.0E-5".
        assertEquals("0.00001000", FixedPoint.toPlainString(FixedPoint.fromDouble(0.00001), 8));
        assertEquals("0.00000001", FixedPoint.toPlainString(1L, 8));
        assertEquals("0", FixedPoint.toPlainString(0L, 0));
    }

    @Test
    void toPlainStringHandlesLargeMagnitudesPlainly() {
        // A quantity/notional at or above 1e7 is where Double.toString also switches to exponent form.
        assertEquals("12345678.12345678", FixedPoint.toPlainString(FixedPoint.parse("12345678.12345678"), 8));
        assertEquals("77850.00", FixedPoint.toPlainString(FixedPoint.fromDouble(77850.0), 2));
    }

    @Test
    void toPlainStringTruncatesToTheRequestedDecimalsWithoutRounding() {
        // Symbol precision (decimals) is a hard venue constraint -- values must already be
        // quantized to a step at or coarser than `decimals` (Sizer.quantizeDown's contract), so
        // truncation here is a safety net, never expected to discard a significant digit in
        // practice.
        assertEquals("1.23", FixedPoint.toPlainString(FixedPoint.parse("1.239999"), 2));
        assertEquals("100", FixedPoint.toPlainString(FixedPoint.fromDouble(100.0), 0));
    }

    @Test
    void toPlainStringRejectsNegativeAndOutOfRangeDecimals() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedPoint.toPlainString(-1L, 4));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedPoint.toPlainString(1L, 9));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> FixedPoint.toPlainString(1L, -1));
    }
}

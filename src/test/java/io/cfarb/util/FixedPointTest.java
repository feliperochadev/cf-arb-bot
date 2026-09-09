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
    void mulDivOverflowBranchIsAllocationFreeAndMatchesRealBtcusdtLevelNotional() {
        // Third-pass review finding (NEW-6): a single BTCUSDT-sized top-of-book level already
        // overflows a plain a*b -- price=77855.52 (real fixture value, mexc_depth_00.pb.bin) and
        // qty=6.77949504 (same fixture), both 1e8-fixed, is exactly Sizer.fillAsk's
        // mulDiv(price, qty, SCALE) call on the Netty event-loop thread. Cross-checked against
        // BigInteger (the previous implementation) to confirm the allocation-free 128-bit division
        // is bit-for-bit identical, not merely "close enough".
        long price = FixedPoint.fromDouble(77855.52);
        long qty = FixedPoint.fromDouble(6.77949504);
        long expected = java.math.BigInteger.valueOf(price).multiply(java.math.BigInteger.valueOf(qty))
                .divide(java.math.BigInteger.valueOf(FixedPoint.SCALE)).longValueExact();
        assertEquals(expected, FixedPoint.mulDiv(price, qty, FixedPoint.SCALE));
        // Sanity: ~527,821.11 USDT notional for that one level (77855.52 * 6.77949504).
        assertEquals(527821.11, FixedPoint.toDouble(expected), 0.01);
    }

    @Test
    void mulDivOverflowBranchMatchesBigIntegerAcrossARandomSweep() {
        // Deterministic seed -- a property-style cross-check against the previous BigInteger
        // implementation over many overflow-triggering (a, b, c) triples, not just the two hand
        // -picked cases above. Every triple is non-negative, matching the only domain mulDiv is
        // ever actually called with in this codebase.
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(20260909L);
        for (int i = 0; i < 100_000; i++) {
            long a = rnd.nextLong(1L, Long.MAX_VALUE);
            long b = rnd.nextLong(1L, Long.MAX_VALUE);
            long c = rnd.nextLong(1L, Long.MAX_VALUE);
            long high = Math.multiplyHigh(a, b);
            long low = a * b;
            boolean overflow = high != (low >> 63);
            if (!overflow) {
                continue; // only the overflow branch is under test here
            }
            java.math.BigInteger expected = java.math.BigInteger.valueOf(a).multiply(java.math.BigInteger.valueOf(b))
                    .divide(java.math.BigInteger.valueOf(c));
            if (expected.bitLength() >= 63) {
                continue; // result itself wouldn't fit a long -- not a shape mulDiv's callers produce
            }
            long actual = FixedPoint.mulDiv(a, b, c);
            assertEquals(expected.longValueExact(), actual,
                    () -> "mismatch for a=" + a + " b=" + b + " c=" + c);
        }
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

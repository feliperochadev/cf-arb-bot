package io.cfarb.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.book.L2Book;
import io.cfarb.feed.MexcDepthDecoder;
import io.cfarb.model.SymbolFilter;
import io.cfarb.model.Side;
import io.cfarb.util.FixedPoint;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Third-pass review finding: {@link Sizer#fillLeg} did not previously exist as its own test class,
 * only exercised indirectly through {@code EdgeCalculatorTest}'s single-level books. These tests
 * target the multi-level VWAP walk specifically -- the shape where {@code baseFilled} is accumulated
 * from several LEVELS, each at its own (better) price, while {@code exec.CycleExecutor} submits the
 * whole quantity as a SINGLE IOC limit order at the WORST level touched.
 */
class SizerTest {

    private static SymbolFilter filter(double qtyStep, int qtyDecimals, double minQty, double minNotional,
                                        int priceDecimals, double takerBps) {
        long feeMultiplierFixed = FixedPoint.fromDouble(1.0 - takerBps / 10_000.0);
        return new SymbolFilter("BTCUSDT", "BTC", "USDT", FixedPoint.fromDouble(qtyStep), qtyDecimals,
                FixedPoint.fromDouble(minQty), FixedPoint.fromDouble(minNotional), priceDecimals, takerBps,
                feeMultiplierFixed, Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.005, 0.005);
    }

    private static L2Book book(long warmupUpdates, long warmupSeconds) {
        return new L2Book(warmupUpdates, warmupSeconds);
    }

    private static void seedAsks(L2Book book, double[] prices, double[] qtys) {
        MexcDepthDecoder.DepthFrame f = new MexcDepthDecoder.DepthFrame();
        f.fromVersion = -1;
        f.toVersion = -1;
        f.sendTimeMs = 0;
        f.bidCount = 1;
        f.bidPx[0] = FixedPoint.fromDouble(prices[0] - 1.0);
        f.bidQty[0] = FixedPoint.fromDouble(1_000.0);
        f.askCount = prices.length;
        for (int i = 0; i < prices.length; i++) {
            f.askPx[i] = FixedPoint.fromDouble(prices[i]);
            f.askQty[i] = FixedPoint.fromDouble(qtys[i]);
        }
        book.apply(f, System.nanoTime());
    }

    @Test
    void multiLevelWalkCapsQuantitySoTheWorstPriceNotionalNeverExceedsBudget() {
        // Level 0: 0.0005 BTC @ 100 (notional 0.05) -- budget exhausts THIS level almost instantly.
        // Level 1: 10 BTC @ 102 -- ample depth, but priced higher.
        // Budget: 100 (USDT). Uncapped, the old code would set baseFilled = 0.0005 + (99.95/102) =
        // ~0.98029..., and baseFilled * worstPrice(102) = ~99.99 -- close to budget here, so widen
        // the gap: use a level 0 notional far below budget so the discrepancy is unambiguous.
        L2Book b = book(1, 0);
        seedAsks(b, new double[]{50.0, 102.0}, new double[]{0.0005, 10.0});
        SymbolFilter filter = filter(1e-6, 6, 1e-6, 1.0, 2, 5.0);
        long budget = FixedPoint.fromDouble(100.0);

        Sizer.Result out = new Sizer.Result();
        Sizer.fillLeg(b, Side.ASK, filter, budget, out);

        assertTrue(out.filled);
        long worstPrice = out.worstPriceFixed;
        long notionalAtWorstPrice = FixedPoint.mulDiv(out.baseQtyFixed, worstPrice, FixedPoint.SCALE);
        assertTrue(notionalAtWorstPrice <= budget,
                "a single IOC limit order for baseQtyFixed at worstPrice must never reserve more than "
                        + "the modeled budget -- notional=" + FixedPoint.toDouble(notionalAtWorstPrice)
                        + " budget=" + FixedPoint.toDouble(budget));
        // Sanity: the cap should bind here (level 0's cheap 0.0005 BTC pulls VWAP well under
        // worstPrice, so the uncapped baseFilled would have priced out over budget at 102).
        long uncappedBaseFilled = FixedPoint.fromDouble(0.0005)
                + FixedPoint.mulDiv(budget - FixedPoint.fromDouble(0.05), FixedPoint.SCALE, FixedPoint.fromDouble(102.0));
        assertTrue(out.baseQtyFixed < uncappedBaseFilled, "the cap must have reduced the quantity below "
                + "what the uncapped VWAP walk would have produced");
    }

    @Test
    void singleLevelFillIsUnaffectedByTheCap() {
        // The common case -- one level absorbs the whole budget, worstPrice IS the only price
        // touched, so the cap must be a no-op (bit-identical to the pre-fix quantity).
        L2Book b = book(1, 0);
        seedAsks(b, new double[]{77850.0}, new double[]{10.0});
        SymbolFilter filter = filter(1e-6, 6, 1e-6, 1.0, 2, 5.0);
        long budget = FixedPoint.fromDouble(100.0);

        Sizer.Result out = new Sizer.Result();
        Sizer.fillLeg(b, Side.ASK, filter, budget, out);

        assertTrue(out.filled);
        long expectedBase = FixedPoint.quantizeDown(
                FixedPoint.mulDiv(budget, FixedPoint.SCALE, FixedPoint.fromDouble(77850.0)), filter.qtyStep());
        assertEquals(expectedBase, out.baseQtyFixed);
    }
}

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

    // --- DYNAMIC-SIZING-TASK.md Phase 2: candidateInputs -------------------------------------------

    private static void seedBids(L2Book book, double[] bidPx, double[] bidQty) {
        MexcDepthDecoder.DepthFrame f = new MexcDepthDecoder.DepthFrame();
        f.fromVersion = -1;
        f.toVersion = -1;
        f.sendTimeMs = 0;
        f.askCount = 1;
        f.askPx[0] = FixedPoint.fromDouble(bidPx[0] + 1.0);
        f.askQty[0] = FixedPoint.fromDouble(1_000.0);
        f.bidCount = bidPx.length;
        for (int i = 0; i < bidPx.length; i++) {
            f.bidPx[i] = FixedPoint.fromDouble(bidPx[i]);
            f.bidQty[i] = FixedPoint.fromDouble(bidQty[i]);
        }
        book.apply(f, System.nanoTime());
    }

    @Test
    void candidateInputsOnAnAskBookReturnsAscendingCumulativeNotionalsClampedDeduped() {
        L2Book b = book(1, 0);
        // level0: 100 @ 10.0 -> notional 1000; level1: 100 @ 20.0 -> notional 2000, cumulative 3000.
        seedAsks(b, new double[]{10.0, 20.0}, new double[]{100.0, 100.0});
        long maxInput = FixedPoint.fromDouble(2_500.0); // between the two natural boundaries
        long[] out = new long[8];

        int n = Sizer.candidateInputs(b, Side.ASK, maxInput, out);

        assertEquals(2, n);
        assertEquals(1_000.0, FixedPoint.toDouble(out[0]), 0.01, "first boundary: level 0's own notional");
        assertEquals(2_500.0, FixedPoint.toDouble(out[1]), 0.01, "second candidate clamped to maxInput");
        assertTrue(out[0] < out[1], "ascending");
    }

    @Test
    void candidateInputsOnABidBookReturnsCumulativeQuantities() {
        L2Book b = book(1, 0);
        seedBids(b, new double[]{10.0, 9.0}, new double[]{50.0, 50.0}); // cumulative qty 50, then 100
        long maxInput = FixedPoint.fromDouble(1_000.0); // never reached by the book's own depth
        long[] out = new long[8];

        int n = Sizer.candidateInputs(b, Side.BID, maxInput, out);

        assertEquals(3, n);
        assertEquals(50.0, FixedPoint.toDouble(out[0]), 1e-6, "first boundary: level 0's own quantity");
        assertEquals(100.0, FixedPoint.toDouble(out[1]), 1e-6, "second boundary: cumulative through level 1");
        assertEquals(1_000.0, FixedPoint.toDouble(out[2]), 1e-6, "maxInput always appended, even beyond depth");
    }

    @Test
    void candidateInputsRespectsTheEightSlotBoundAndAlwaysIncludesMaxInput() {
        L2Book b = book(1, 0);
        double[] px = new double[20];
        double[] qty = new double[20];
        for (int i = 0; i < 20; i++) {
            px[i] = 10.0 + i; // each level's notional is small relative to maxInput
            qty[i] = 1.0;
        }
        seedAsks(b, px, qty);
        long maxInput = FixedPoint.fromDouble(1_000_000.0); // never reached within 20 shallow levels
        long[] out = new long[8];

        int n = Sizer.candidateInputs(b, Side.ASK, maxInput, out);

        assertEquals(8, n, "bounded to the 8-slot array");
        assertEquals(1_000_000.0, FixedPoint.toDouble(out[7]), 0.01, "maxInput occupies the final slot");
        for (int i = 0; i < 6; i++) {
            assertTrue(out[i] < out[i + 1], "ascending through the natural boundaries: index " + i);
        }
    }

    @Test
    void candidateInputsOnASingleLevelBookReturnsExactlyOneCandidate() {
        L2Book b = book(1, 0);
        seedAsks(b, new double[]{10.0}, new double[]{10_000.0}); // ample depth, well beyond maxInput
        long maxInput = FixedPoint.fromDouble(500.0);
        long[] out = new long[8];

        int n = Sizer.candidateInputs(b, Side.ASK, maxInput, out);

        assertEquals(1, n);
        assertEquals(500.0, FixedPoint.toDouble(out[0]), 0.01);
    }

    // --- PRE-LIVE-PLAN.md P0-1: ConsumptionLedger integration ---------------------------------

    private static void applyAsksAt(L2Book book, double[] prices, double[] qtys, long nowNanos) {
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
        book.apply(f, nowNanos);
    }

    /** Mirrors what {@code OpportunityDetector.recordConsumption} does after a successful fire --
     * commits a {@link Sizer.Result}'s per-level breakdown into the ledger. */
    private static void commit(ConsumptionLedger ledger, int symbolIndex, Side side, Sizer.Result out, long nowNanos) {
        for (int i = 0; i < out.levelsTouched; i++) {
            ledger.record(symbolIndex, side, out.levelIndex[i], out.levelWriteSeq[i], out.levelQtyConsumed[i], nowNanos);
        }
    }

    @Test
    void aLegThatFillsAtSizeNOnceCannotFillAtNAgainFromTheSameUnchangedLadder() {
        L2Book b = book(1, 0);
        long t0 = 10_000_000_000L;
        applyAsksAt(b, new double[]{100.0}, new double[]{1.0}, t0); // 1.0 BTC @ 100 -> $100 total depth
        SymbolFilter filter = filter(1e-6, 6, 1e-6, 1.0, 2, 5.0);
        long budget = FixedPoint.fromDouble(70.0); // more than half the level's depth
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        int symbolIndex = 3;

        Sizer.Result first = new Sizer.Result();
        Sizer.fillLeg(b, Side.ASK, filter, budget, first, ledger, symbolIndex, t0);
        assertTrue(first.filled, "the first $70 fill must succeed against $100 of depth");
        commit(ledger, symbolIndex, Side.ASK, first, t0);

        // Same book (nothing rewrote the level), same budget, a moment later -- only $30 of real
        // depth remains after the ledger's claim, not enough to fill $70 in full.
        Sizer.Result second = new Sizer.Result();
        Sizer.fillLeg(b, Side.ASK, filter, budget, second, ledger, symbolIndex, t0 + 1_000_000L);
        assertTrue(!second.filled, "the SAME $70 must not fill again from the same, now largely-claimed, level");
    }

    @Test
    void theSameLegFillsAgainOnceTheLevelsWriteSeqAdvances() {
        L2Book b = book(1, 0);
        long t0 = 10_000_000_000L;
        applyAsksAt(b, new double[]{100.0}, new double[]{1.0}, t0);
        SymbolFilter filter = filter(1e-6, 6, 1e-6, 1.0, 2, 5.0);
        long budget = FixedPoint.fromDouble(70.0);
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        int symbolIndex = 3;

        Sizer.Result first = new Sizer.Result();
        Sizer.fillLeg(b, Side.ASK, filter, budget, first, ledger, symbolIndex, t0);
        assertTrue(first.filled);
        commit(ledger, symbolIndex, Side.ASK, first, t0);

        Sizer.Result blocked = new Sizer.Result();
        Sizer.fillLeg(b, Side.ASK, filter, budget, blocked, ledger, symbolIndex, t0 + 1_000_000L);
        assertTrue(!blocked.filled, "sanity: still blocked before the level is rewritten");

        // The venue replenishes the SAME price level (an in-place qty update bumps its writeSeq) --
        // genuinely new liquidity, so the old claim must no longer apply.
        long t1 = t0 + 2_000_000L;
        applyAsksAt(b, new double[]{100.0}, new double[]{1.0}, t1);

        Sizer.Result third = new Sizer.Result();
        Sizer.fillLeg(b, Side.ASK, filter, budget, third, ledger, symbolIndex, t1);
        assertTrue(third.filled, "a rewritten level must fill again, the old claim no longer applies");
    }

    @Test
    void aNullLedgerReproducesUnclaimedBehaviourExactly() {
        // The live-mode path: passing ledger=null must behave identically to the pre-P0-1 5-arg form.
        L2Book b = book(1, 0);
        long t0 = 10_000_000_000L;
        applyAsksAt(b, new double[]{100.0}, new double[]{1.0}, t0);
        SymbolFilter filter = filter(1e-6, 6, 1e-6, 1.0, 2, 5.0);
        long budget = FixedPoint.fromDouble(70.0);

        Sizer.Result viaFiveArg = new Sizer.Result();
        Sizer.fillLeg(b, Side.ASK, filter, budget, viaFiveArg);

        Sizer.Result viaNullLedger = new Sizer.Result();
        Sizer.fillLeg(b, Side.ASK, filter, budget, viaNullLedger, null, -1, 0L);

        assertTrue(viaFiveArg.filled && viaNullLedger.filled);
        assertEquals(viaFiveArg.baseQtyFixed, viaNullLedger.baseQtyFixed);
        assertEquals(viaFiveArg.worstPriceFixed, viaNullLedger.worstPriceFixed);
        assertEquals(viaFiveArg.quoteFixed, viaNullLedger.quoteFixed);
        assertEquals(viaFiveArg.maxWriteSeq, viaNullLedger.maxWriteSeq);
        assertEquals(0, viaNullLedger.levelsTouched, "a null ledger must never populate the per-level breakdown");

        // Firing the SAME budget again must behave identically too -- a null ledger never blocks.
        Sizer.Result again = new Sizer.Result();
        Sizer.fillLeg(b, Side.ASK, filter, budget, again, null, -1, 0L);
        assertTrue(again.filled, "no ledger -- the same size must fill every time, unaffected by any prior fire");
        assertEquals(viaFiveArg.baseQtyFixed, again.baseQtyFixed);
    }

    @Test
    void multiLevelConsumptionBreakdownIsClippedToTheFinalCappedFillNeverOverReported() {
        // Mirrors multiLevelWalkCapsQuantitySoTheWorstPriceNotionalNeverExceedsBudget's shape: level
        // 0 is cheap and shallow, level 1 deep but pricier -- the worst-price cap shrinks baseFilled
        // below what the raw per-level walk would suggest. The recorded breakdown's sum must match
        // the FINAL (capped) baseQtyFixed exactly, never more.
        L2Book b = book(1, 0);
        long t0 = 10_000_000_000L;
        applyAsksAt(b, new double[]{50.0, 102.0}, new double[]{0.0005, 10.0}, t0);
        SymbolFilter filter = filter(1e-6, 6, 1e-6, 1.0, 2, 5.0);
        long budget = FixedPoint.fromDouble(100.0);
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);

        Sizer.Result out = new Sizer.Result();
        Sizer.fillLeg(b, Side.ASK, filter, budget, out, ledger, 5, t0);

        assertTrue(out.filled);
        long sumRecorded = 0;
        for (int i = 0; i < out.levelsTouched; i++) {
            sumRecorded += out.levelQtyConsumed[i];
        }
        assertEquals(out.baseQtyFixed, sumRecorded,
                "the recorded per-level breakdown must sum to exactly the final (capped) fill, never more");
    }
}

package io.cfarb.strategy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.book.BookRegistry;
import io.cfarb.book.L2Book;
import io.cfarb.feed.MexcDepthDecoder;
import io.cfarb.graph.Triangle;
import io.cfarb.model.Side;
import io.cfarb.model.SymbolFilter;
import io.cfarb.util.FixedPoint;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Cross-checks {@link EdgeCalculator} against the Python research pipeline's exact log-space
 * formula ({@code cfarb.stage2_cycles.evaluate_cycle}: {@code net_bps = (Π rate_i * (1-fee_i) - 1)
 * * 10000}), plus known-answer tests for the lot-size quantization that Python model never had
 * (cf-arb-bot-plan.md §11 verification items 2-3).
 */
class EdgeCalculatorTest {

    private static void seedBook(L2Book book, double bidPx, double bidQty, double askPx, double askQty) {
        MexcDepthDecoder.DepthFrame f = new MexcDepthDecoder.DepthFrame();
        f.bidCount = 1;
        f.bidPx[0] = FixedPoint.fromDouble(bidPx);
        f.bidQty[0] = FixedPoint.fromDouble(bidQty);
        f.askCount = 1;
        f.askPx[0] = FixedPoint.fromDouble(askPx);
        f.askQty[0] = FixedPoint.fromDouble(askQty);
        f.fromVersion = -1;
        f.toVersion = -1;
        f.sendTimeMs = 0;
        book.apply(f, System.nanoTime()); // warmupUpdates=1 in these tests -> trusted immediately
    }

    private static SymbolFilter filter(String symbol, String base, String quote, long qtyStep, int qtyDecimals,
                                        long minQty, long minNotional, int priceDecimals, double takerBps) {
        long feeMultiplierFixed = FixedPoint.fromDouble(1.0 - takerBps / 10_000.0);
        return new SymbolFilter(symbol, base, quote, qtyStep, qtyDecimals, minQty, minNotional, priceDecimals,
                takerBps, feeMultiplierFixed, Set.of("LIMIT", "MARKET", "LIMIT_MAKER"), 0.05, 0.05);
    }

    private static Triangle triangle(String name, int[] symbolIndex, Side[] sides, SymbolFilter[] filters) {
        String[] fromAsset = new String[3];
        String[] toAsset = new String[3];
        for (int i = 0; i < 3; i++) {
            fromAsset[i] = Triangle.legFromAsset(sides[i], filters[i]);
            toAsset[i] = Triangle.legToAsset(sides[i], filters[i]);
        }
        return new Triangle(name, symbolIndex, sides, filters, fromAsset, toAsset);
    }

    /** Multi-level ask ladder (best-first) plus a single, irrelevant bid level -- for
     * DYNAMIC-SIZING-TASK.md Phase 2's leg-0 ladder-boundary search. */
    private static void seedAsks(L2Book book, double bidPx, double bidQty, double[] askPx, double[] askQty) {
        MexcDepthDecoder.DepthFrame f = new MexcDepthDecoder.DepthFrame();
        f.bidCount = 1;
        f.bidPx[0] = FixedPoint.fromDouble(bidPx);
        f.bidQty[0] = FixedPoint.fromDouble(bidQty);
        f.askCount = askPx.length;
        for (int i = 0; i < askPx.length; i++) {
            f.askPx[i] = FixedPoint.fromDouble(askPx[i]);
            f.askQty[i] = FixedPoint.fromDouble(askQty[i]);
        }
        f.fromVersion = -1;
        f.toVersion = -1;
        f.sendTimeMs = 0;
        book.apply(f, System.nanoTime());
    }

    @Test
    void matchesPythonLogSpaceFormulaForAContinuousBook() {
        // Prices/fees mirror a real MEXC XRP triangle (ETHUSDT ask / XRPETH ask / XRPUSDT bid),
        // taker fees from the LIVE exchangeInfo pull (5.0 / 5.0 / 0.0 bps — cf-arb-bot-plan.md §2.2).
        // Independently computed in Python:
        //   log_edge = log(1/2450.00) + log(1-0.0005) + log(1/0.00057) + log(1-0.0005)
        //            + log(1.40) + log(1-0.0)
        //   net_bps = (exp(log_edge) - 1) * 10000 = 15.0401002506
        List<String> symbols = List.of("ETHUSDT", "XRPETH", "XRPUSDT");
        BookRegistry books = new BookRegistry(symbols, 1, 0);
        seedBook(books.book(0), 2449.00, 10.0, 2450.00, 10.0);   // ETHUSDT
        seedBook(books.book(1), 0.00056, 100_000.0, 0.00057, 100_000.0); // XRPETH
        seedBook(books.book(2), 1.40, 1_000_000.0, 1.41, 1_000_000.0);   // XRPUSDT

        // Negligible quantization: tiny step/minQty/minNotional so the fixed-point ladder walk
        // behaves as "continuous" like the Python model, isolating the fee/log-space math.
        SymbolFilter ethusdt = filter("ETHUSDT", "ETH", "USDT", 1L, 8, 0L, 0L, 2, 5.0);
        SymbolFilter xrpeth = filter("XRPETH", "XRP", "ETH", 1L, 8, 0L, 0L, 7, 5.0);
        SymbolFilter xrpusdt = filter("XRPUSDT", "XRP", "USDT", 1L, 8, 0L, 0L, 4, 0.0);

        Triangle tri = triangle("test-eth-xrp",
                new int[]{0, 1, 2},
                new Side[]{Side.ASK, Side.ASK, Side.BID},
                new SymbolFilter[]{ethusdt, xrpeth, xrpusdt});

        EdgeCalculator calc = new EdgeCalculator();
        EdgeCalculator.Result out = new EdgeCalculator.Result();
        long start = FixedPoint.fromDouble(1000.0); // small vs. the seeded depth -> no VWAP slippage
        calc.evaluate(tri, books, start, out);

        assertTrue(out.fillable);
        assertEquals(15.0401002506, out.netBps, 0.01, "must match Python's log-space formula to within rounding");
        // grossBps is the SAME top-of-book log-space product as stage2_cycles.evaluate_cycle. With a
        // single deep level per side and a $1000 start (no VWAP slippage, negligible quantization) it
        // must land on the same number as netBps.
        assertEquals(15.0401002506, out.grossBps, 0.01,
                "grossBps must match the Python evaluate_cycle formula (top-of-book, pre-slippage)");
    }

    @Test
    void solBtcTriangleRejectedAt100SeedAcceptedAt500Seed() {
        // The plan's headline finding (cf-arb-bot-plan.md §2.1): SOLBTC's minimum order is 1 whole
        // SOL, so a $100 seed cannot place it, even with a deliberate large edge available. Uses
        // MEXC's REAL live filter values (re-verified 2026-09-07 against GET /api/v3/exchangeInfo,
        // cf-arb-bot-review-plan.md Tier 1 step 1.3), not a synthetic qtyStep, and the plan's exact
        // $500 acceptance threshold rather than an arbitrarily larger one.
        List<String> symbols = List.of("BTCUSDT", "SOLBTC", "SOLUSDT");
        BookRegistry books = new BookRegistry(symbols, 1, 0);
        seedBook(books.book(0), 77800.0, 5.0, 77850.0, 5.0);   // BTCUSDT
        seedBook(books.book(1), 0.00130, 500.0, 0.00131, 500.0); // SOLBTC (bid < ask, valid book, ample depth)
        seedBook(books.book(2), 103.50, 5000.0, 105.00, 5000.0); // SOLUSDT (bid < ask, valid book, ample depth)

        SymbolFilter btcusdt = filter("BTCUSDT", "BTC", "USDT", FixedPoint.fromDouble(1e-6), 8,
                FixedPoint.fromDouble(1e-6), FixedPoint.fromDouble(1.0), 2, 5.0);
        SymbolFilter solbtc = filter("SOLBTC", "SOL", "BTC", FixedPoint.fromDouble(0.01), 2,
                FixedPoint.fromDouble(1.0), FixedPoint.fromDouble(0.000005), 8, 5.0);
        SymbolFilter solusdt = filter("SOLUSDT", "SOL", "USDT", FixedPoint.fromDouble(0.01), 3,
                FixedPoint.fromDouble(0.01), FixedPoint.fromDouble(1.0), 2, 5.0);

        Triangle tri = triangle("test-solbtc",
                new int[]{0, 1, 2},
                new Side[]{Side.ASK, Side.ASK, Side.BID}, // USDT->BTC->SOL->USDT
                new SymbolFilter[]{btcusdt, solbtc, solusdt});

        EdgeCalculator calc = new EdgeCalculator();
        EdgeCalculator.Result out = new EdgeCalculator.Result();

        calc.evaluate(tri, books, FixedPoint.fromDouble(100.0), out);
        assertFalse(out.fillable, "a $100 seed buys well under 1 SOL via SOLBTC's 0.00130 rate -- "
                + "the SOLBTC leg's real 1-SOL minimum must reject this");
        // rec 3: an unfillable candidate still carries the top-of-book gross edge, so a dry-run
        // journal separates "no edge existed" from "edge existed but wasn't fillable at this size".
        assertTrue(Double.isFinite(out.grossBps),
                "grossBps must be populated even when the candidate is unfillable at size");

        calc.evaluate(tri, books, FixedPoint.fromDouble(500.0), out);
        assertTrue(out.fillable, "at $500 the notional clears SOLBTC's real 1-SOL minimum "
                + "(plan cf-arb-bot-plan.md §5.3's exact known-answer threshold)");
    }

    // --- DYNAMIC-SIZING-TASK.md Phase 1: gross early-out -----------------------------------------

    private static Triangle continuousXrpEthTriangle(BookRegistry books) {
        seedBook(books.book(0), 2449.00, 10.0, 2450.00, 10.0);   // ETHUSDT
        seedBook(books.book(1), 0.00056, 100_000.0, 0.00057, 100_000.0); // XRPETH
        seedBook(books.book(2), 1.40, 1_000_000.0, 1.41, 1_000_000.0);   // XRPUSDT
        SymbolFilter ethusdt = filter("ETHUSDT", "ETH", "USDT", 1L, 8, 0L, 0L, 2, 5.0);
        SymbolFilter xrpeth = filter("XRPETH", "XRP", "ETH", 1L, 8, 0L, 0L, 7, 5.0);
        SymbolFilter xrpusdt = filter("XRPUSDT", "XRP", "USDT", 1L, 8, 0L, 0L, 4, 0.0);
        return triangle("test-eth-xrp", new int[]{0, 1, 2}, new Side[]{Side.ASK, Side.ASK, Side.BID},
                new SymbolFilter[]{ethusdt, xrpeth, xrpusdt});
    }

    @Test
    void bailBelowGrossBpsAboveTheComputedGrossBailsEarlyButStillCarriesGrossAndT4() {
        BookRegistry books = new BookRegistry(List.of("ETHUSDT", "XRPETH", "XRPUSDT"), 1, 0);
        Triangle tri = continuousXrpEthTriangle(books);
        EdgeCalculator calc = new EdgeCalculator();
        EdgeCalculator.Result out = new EdgeCalculator.Result();
        long start = FixedPoint.fromDouble(1000.0);

        // The real gross here is ~15.04bps (see the log-space test above) -- 100.0 is comfortably above it.
        calc.evaluate(tri, books, start, out, 100.0);

        assertTrue(out.bailedEarly, "grossBps (~15.04) cannot clear a 100.0 gate -- must bail");
        assertFalse(out.fillable);
        assertTrue(Double.isNaN(out.netBps));
        assertEquals(15.0401002506, out.grossBps, 0.01, "grossBps is still populated (pass 1 always runs)");
        for (int leg = 0; leg < 3; leg++) {
            assertTrue(out.legTopPriceFixed[leg] != 0, "T4 leg-top price still populated for leg " + leg);
            assertTrue(out.legTouchQtyFixed[leg] != 0, "T4 touch qty still populated for leg " + leg);
            assertEquals(0L, out.legWorstPriceFixed[leg], "the ladder walk never ran -- no worst price for leg " + leg);
        }
    }

    @Test
    void bailBelowGrossBpsBelowTheComputedGrossMatchesThePlainFourArgCallFieldByField() {
        BookRegistry books = new BookRegistry(List.of("ETHUSDT", "XRPETH", "XRPUSDT"), 1, 0);
        Triangle tri = continuousXrpEthTriangle(books);
        EdgeCalculator calc = new EdgeCalculator();
        long start = FixedPoint.fromDouble(1000.0);

        EdgeCalculator.Result plain = new EdgeCalculator.Result();
        calc.evaluate(tri, books, start, plain);

        EdgeCalculator.Result withBail = new EdgeCalculator.Result();
        calc.evaluate(tri, books, start, withBail, -100.0); // comfortably below the real ~15.04 gross

        assertFalse(withBail.bailedEarly);
        assertEquals(plain.fillable, withBail.fillable);
        assertEquals(plain.finalAmount, withBail.finalAmount);
        assertEquals(plain.netBps, withBail.netBps);
        assertEquals(plain.grossBps, withBail.grossBps);
        assertArrayEquals(plain.legInputAmount, withBail.legInputAmount);
        assertArrayEquals(plain.legWorstPriceFixed, withBail.legWorstPriceFixed);
        assertArrayEquals(plain.legBaseQtyFixed, withBail.legBaseQtyFixed);
        assertArrayEquals(plain.legTopPriceFixed, withBail.legTopPriceFixed);
        assertArrayEquals(plain.legTouchQtyFixed, withBail.legTouchQtyFixed);
        assertArrayEquals(plain.legWriteSeq, withBail.legWriteSeq);
    }

    // --- DYNAMIC-SIZING-TASK.md Phase 2: dynamic per-tick sizing ---------------------------------

    /** A 2-level leg-0 ladder (touch: price 0.990/qty 700 -> ~101bps; deeper: price 0.997/qty
     * 100,000 -> ~30bps) with continuous, fee-free pass-through legs 1/2, so the WHOLE triangle's
     * economics come from leg 0 alone. Sizer's own worst-price cap (see Sizer.fillAsk's javadoc)
     * means any size that draws from level 1 at all is priced (for cap purposes) at level 1's
     * worse rate -- so profit(N) is NOT smooth across the level-0/level-1 boundary, but it is still
     * true, and is exactly what this fixture demonstrates, that a LARGER size at a LOWER bps
     * (30bps @ $4000 -> $12.04 profit) beats a SMALLER size at a HIGHER bps (101bps @ $693 ->
     * $7.00 profit) in absolute dollars -- the motivating case for optimising dollars, not bps.
     */
    private static Triangle dollarOptimumTriangle(BookRegistry books) {
        seedAsks(books.book(0), 0.5, 1.0, new double[]{0.990, 0.997}, new double[]{700.0, 100_000.0});
        seedAsks(books.book(1), 0.999999, 1.0, new double[]{1.0}, new double[]{1_000_000.0});
        seedAsks(books.book(2), 1.0, 1_000_000.0, new double[]{1.000001}, new double[]{1.0});
        SymbolFilter xusdt = filter("XUSDT", "X", "USDT", 1L, 8, 0L, 0L, 8, 0.0);
        SymbolFilter yx = filter("YX", "Y", "X", 1L, 8, 0L, 0L, 8, 0.0);
        SymbolFilter yusdt = filter("YUSDT", "Y", "USDT", 1L, 8, 0L, 0L, 8, 0.0);
        return triangle("test-dollar-optimum", new int[]{0, 1, 2},
                new Side[]{Side.ASK, Side.ASK, Side.BID}, new SymbolFilter[]{xusdt, yx, yusdt});
    }

    @Test
    void evaluateBestSizePicksTheLargerDollarOptimumOverTheHigherBpsTouchSize() {
        BookRegistry books = new BookRegistry(List.of("XUSDT", "YX", "YUSDT"), 1, 0);
        Triangle tri = dollarOptimumTriangle(books);
        EdgeCalculator calc = new EdgeCalculator();
        EdgeCalculator.Result out = new EdgeCalculator.Result();
        long maxStart = FixedPoint.fromDouble(4000.0);

        calc.evaluateBestSize(tri, books, maxStart, out, Double.NEGATIVE_INFINITY, 0L);

        assertTrue(out.fillable);
        assertTrue(out.sizeCandidates >= 2, "leg 0's second level must produce a second candidate");
        assertEquals(4000.0, FixedPoint.toDouble(out.legInputAmount[0]), 0.01,
                "the $4000 candidate (lower bps, higher $ profit) must win, not the $693 touch");
        assertTrue(out.legInputAmount[0] <= maxStart, "never returns a notional above maxStartAmount");
        assertEquals(30.09, out.netBps, 0.01, "the chosen size's own net_bps, lower than the touch's ~101bps");
        assertEquals(out.netBps, out.netBpsAtCap, 1e-9,
                "the chosen size IS the cap here, so net_bps_at_cap must match net_bps exactly");

        // Consistency invariant (non-negotiable #7): a direct fixed-size evaluate() at the notional
        // the search chose must reproduce an IDENTICAL Result -- proving the search returns a real
        // evaluation, not a differently-computed one.
        EdgeCalculator.Result direct = new EdgeCalculator.Result();
        calc.evaluate(tri, books, out.legInputAmount[0], direct);
        assertEquals(direct.fillable, out.fillable);
        assertEquals(direct.finalAmount, out.finalAmount);
        assertEquals(direct.netBps, out.netBps);
        assertEquals(direct.grossBps, out.grossBps);
        assertArrayEquals(direct.legInputAmount, out.legInputAmount);
        assertArrayEquals(direct.legWorstPriceFixed, out.legWorstPriceFixed);
        assertArrayEquals(direct.legBaseQtyFixed, out.legBaseQtyFixed);
        assertArrayEquals(direct.legTopPriceFixed, out.legTopPriceFixed);
        assertArrayEquals(direct.legTouchQtyFixed, out.legTouchQtyFixed);
        assertArrayEquals(direct.legWriteSeq, out.legWriteSeq);
    }

    @Test
    void evaluateBestSizeFallsBackToASmallerCandidateWhenTheLargestIsUnfillable() {
        BookRegistry books = new BookRegistry(List.of("XUSDT", "YX", "YUSDT"), 1, 0);
        seedAsks(books.book(0), 0.5, 1.0, new double[]{0.990, 0.997}, new double[]{700.0, 100_000.0});
        seedAsks(books.book(1), 0.999999, 1.0, new double[]{1.0}, new double[]{1_000_000.0});
        // Leg 2's bid depth (750 Y) absorbs the small candidate's ~700 Y but not the large
        // candidate's ~4012 Y -- the largest candidate is unfillable purely on displayed depth.
        seedAsks(books.book(2), 1.0, 750.0, new double[]{1.000001}, new double[]{1.0});
        SymbolFilter xusdt = filter("XUSDT", "X", "USDT", 1L, 8, 0L, 0L, 8, 0.0);
        SymbolFilter yx = filter("YX", "Y", "X", 1L, 8, 0L, 0L, 8, 0.0);
        SymbolFilter yusdt = filter("YUSDT", "Y", "USDT", 1L, 8, 0L, 0L, 8, 0.0);
        Triangle tri = triangle("test-fallback", new int[]{0, 1, 2},
                new Side[]{Side.ASK, Side.ASK, Side.BID}, new SymbolFilter[]{xusdt, yx, yusdt});

        EdgeCalculator calc = new EdgeCalculator();
        EdgeCalculator.Result out = new EdgeCalculator.Result();
        calc.evaluateBestSize(tri, books, FixedPoint.fromDouble(4000.0), out, Double.NEGATIVE_INFINITY, 0L);

        assertTrue(out.fillable, "the $693 candidate must still be picked even though $4000 fails leg 2's depth");
        assertEquals(693.0, FixedPoint.toDouble(out.legInputAmount[0]), 0.5);
        assertTrue(Double.isNaN(out.netBpsAtCap), "the cap candidate itself was unfillable -- net_bps_at_cap is NaN");
    }

    @Test
    void evaluateBestSizeAllCandidatesUnfillableLeavesResultNotFillable() {
        BookRegistry books = new BookRegistry(List.of("XUSDT", "YX", "YUSDT"), 1, 0);
        seedAsks(books.book(0), 0.5, 1.0, new double[]{0.990, 0.997}, new double[]{700.0, 100_000.0});
        seedAsks(books.book(1), 0.999999, 1.0, new double[]{1.0}, new double[]{1_000_000.0});
        // Leg 2's bid depth (1 Y) can't absorb even the smallest candidate.
        seedAsks(books.book(2), 1.0, 1.0, new double[]{1.000001}, new double[]{1.0});
        SymbolFilter xusdt = filter("XUSDT", "X", "USDT", 1L, 8, 0L, 0L, 8, 0.0);
        SymbolFilter yx = filter("YX", "Y", "X", 1L, 8, 0L, 0L, 8, 0.0);
        SymbolFilter yusdt = filter("YUSDT", "Y", "USDT", 1L, 8, 0L, 0L, 8, 0.0);
        Triangle tri = triangle("test-all-unfillable", new int[]{0, 1, 2},
                new Side[]{Side.ASK, Side.ASK, Side.BID}, new SymbolFilter[]{xusdt, yx, yusdt});

        EdgeCalculator calc = new EdgeCalculator();
        EdgeCalculator.Result out = new EdgeCalculator.Result();
        calc.evaluateBestSize(tri, books, FixedPoint.fromDouble(4000.0), out, Double.NEGATIVE_INFINITY, 0L);

        assertFalse(out.fillable);
        assertTrue(Double.isNaN(out.netBps));
    }

    @Test
    void evaluateBestSizeGrossBelowTheGateBailsEarlyWithZeroLadderWalks() {
        BookRegistry books = new BookRegistry(List.of("XUSDT", "YX", "YUSDT"), 1, 0);
        Triangle tri = dollarOptimumTriangle(books);
        EdgeCalculator calc = new EdgeCalculator();
        EdgeCalculator.Result out = new EdgeCalculator.Result();

        // The real gross here is ~101.01bps (top-of-book) -- 200.0 is comfortably above it.
        calc.evaluateBestSize(tri, books, FixedPoint.fromDouble(4000.0), out, 200.0, 0L);

        assertTrue(out.bailedEarly);
        assertFalse(out.fillable);
        assertEquals(0, out.sizeCandidates, "the ladder-boundary search never ran");
        assertTrue(Double.isNaN(out.netBpsAtCap));
        for (long worstPx : out.legWorstPriceFixed) {
            assertEquals(0L, worstPx, "no ladder walk means no worst price was ever touched");
        }
    }

    // --- PRE-LIVE-PLAN.md P0-1: the 4-arg evaluate() gate ---------------------------------------

    @Test
    void theFourArgEvaluateWithANullLedgerProducesAnIdenticalResultToBeforeP01() {
        // The exact gate PRE-LIVE-PLAN.md C6 names: a fresh EdgeCalculator (consumptionLedger stays
        // null, the default -- setConsumptionLedger is never called by this test) must reproduce the
        // Python-cross-checked continuous-book scenario field-by-field, proving P0-1 is additive.
        List<String> symbols = List.of("ETHUSDT", "XRPETH", "XRPUSDT");
        BookRegistry books = new BookRegistry(symbols, 1, 0);
        seedBook(books.book(0), 2449.00, 10.0, 2450.00, 10.0);
        seedBook(books.book(1), 0.00056, 100_000.0, 0.00057, 100_000.0);
        seedBook(books.book(2), 1.40, 1_000_000.0, 1.41, 1_000_000.0);

        SymbolFilter ethusdt = filter("ETHUSDT", "ETH", "USDT", 1L, 8, 0L, 0L, 2, 5.0);
        SymbolFilter xrpeth = filter("XRPETH", "XRP", "ETH", 1L, 8, 0L, 0L, 7, 5.0);
        SymbolFilter xrpusdt = filter("XRPUSDT", "XRP", "USDT", 1L, 8, 0L, 0L, 4, 0.0);
        Triangle tri = triangle("test-eth-xrp", new int[]{0, 1, 2},
                new Side[]{Side.ASK, Side.ASK, Side.BID},
                new SymbolFilter[]{ethusdt, xrpeth, xrpusdt});

        EdgeCalculator calc = new EdgeCalculator();
        EdgeCalculator.Result out = new EdgeCalculator.Result();
        long start = FixedPoint.fromDouble(1000.0);
        calc.evaluate(tri, books, start, out);

        assertTrue(out.fillable);
        assertEquals(15.0401002506, out.netBps, 0.01);
        assertEquals(15.0401002506, out.grossBps, 0.01);
        for (int leg = 0; leg < 3; leg++) {
            assertEquals(0, out.legLevelsTouched[leg], "no ledger -- the per-level breakdown must never populate");
        }

        // Setting a ledger on a DIFFERENT instance must not somehow leak into this one, and calling
        // evaluate() AGAIN on this same (still ledger-less) instance must reproduce the identical
        // Result -- P0-1 must not introduce any hidden state that makes repeated evaluation diverge.
        EdgeCalculator calcWithLedger = new EdgeCalculator();
        calcWithLedger.setConsumptionLedger(new ConsumptionLedger(4, 5_000));
        EdgeCalculator.Result outFromOtherInstance = new EdgeCalculator.Result();
        calcWithLedger.evaluate(tri, books, start, outFromOtherInstance);
        assertEquals(out.netBps, outFromOtherInstance.netBps, 1e-9,
                "the 4-arg evaluate() never wires a ledger in, even on an instance that has one installed "
                        + "for evaluateBestSize -- see its own javadoc");

        EdgeCalculator.Result second = new EdgeCalculator.Result();
        calc.evaluate(tri, books, start, second);
        assertEquals(out.fillable, second.fillable);
        assertEquals(out.finalAmount, second.finalAmount);
        assertEquals(out.netBps, second.netBps, 1e-12);
        assertArrayEquals(out.legInputAmount, second.legInputAmount);
        assertArrayEquals(out.legWorstPriceFixed, second.legWorstPriceFixed);
        assertArrayEquals(out.legBaseQtyFixed, second.legBaseQtyFixed);
        assertArrayEquals(out.legWriteSeq, second.legWriteSeq);
    }
}

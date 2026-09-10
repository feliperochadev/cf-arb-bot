package io.cfarb.strategy;

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
}

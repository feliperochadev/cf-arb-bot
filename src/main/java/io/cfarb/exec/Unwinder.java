package io.cfarb.exec;

import io.cfarb.book.BookRegistry;
import io.cfarb.graph.Triangle;
import io.cfarb.model.Side;
import io.cfarb.model.SymbolFilter;
import io.cfarb.util.FixedPoint;
import org.jboss.logging.Logger;

/**
 * Recovers from a broken cycle by walking COMPLETED legs in reverse, back to the anchor asset —
 * cf-arb-bot-plan.md §5.4 names this "the single largest engineering risk in this build."
 *
 * <p><b>Rewritten by cf-arb-bot-review-plan.md Tier 1 step 1.4</b> after the FIRST independent
 * review's Major finding: the previous design reversed the FAILED leg's symbol using the amount
 * that was being SENT INTO that leg — for a leg-0 failure, that is the anchor seed itself, submitted
 * as if it were the failed leg's base/quote asset (e.g. treating 100 USDT as 100 BTC and submitting
 * {@code SELL 100 BTC}). A failed leg was never reversed, but the wrong one.
 *
 * <p><b>Rewritten AGAIN by REVIEW.md's second independent review, MAJ-02</b> — that fix correctly
 * identified WHICH leg to reverse, but still priced every reversal at the ORIGINAL leg's own
 * entry-boundary price (the same ladder-walk price boundary that leg requested when it was first
 * submitted, going forward). That fails deterministically in BOTH directions, not just on an
 * adverse move: reversing an ASK leg means SELLing at the very ask price you just BOUGHT at (the
 * market has to rise by the full spread for that to fill); reversing a BID leg means BUYing at the
 * bid you just SOLD at (the market has to fall by the full spread). An emergency reversal must
 * CROSS the book, not sit at a boundary that only fills if the market conveniently reverses course
 * on its own. Reversals now price off the CURRENT top of book ({@link BookRegistry}, published by
 * the feed thread — see {@code book.L2Book#topBidFixed()}'s javadoc and CLAUDE.md non-negotiable
 * #5's declared-hand-off requirement), crossed by {@code cf-bot.exec.unwind-cross-bps} and clamped
 * inside the symbol's own {@code PERCENT_PRICE_BY_SIDE} band so a fat-fingered buffer can never
 * itself trigger a rejection.
 *
 * <p><b>Correct model:</b> in this bot's strictly sequential leg-by-leg execution (abort on the
 * first non-full fill), at most ONE leg's output is ever "in hand and not yet converted forward" at
 * the moment of failure — every earlier leg's output was already fully spent as the next leg's
 * input. That held leg is the largest index {@code <= failedLegIndex} whose status is
 * {@code FILLED} or {@code PARTIAL}:
 * <ul>
 *   <li>None exists (leg 0 itself never acquired anything) — nothing to unwind, nothing was spent.</li>
 *   <li>It is leg 2 — its own proceeds ARE already anchor-denominated (leg 2 always returns to the
 *       anchor by construction, cf-arb-bot-review-plan.md's triangle-closure validation); no order
 *       is placed, the held amount is simply booked as recovered.</li>
 *   <li>Otherwise, reverse that leg's symbol (opposite side, sized to what was actually acquired),
 *       then — since reversing leg {@code i} only returns to {@code fromAsset[i]}, not necessarily
 *       the anchor — continue reversing leg {@code i-1}, {@code i-2}, ... down to leg 0, each step
 *       using the PREVIOUS reversal's actual proceeds. After leg 0's reversal, the amount held is,
 *       by the triangle-closure invariant, anchor-denominated.</li>
 * </ul>
 *
 * <p><b>When there is no live book to price a reversal against</b> (untrusted/empty — e.g. a
 * reconnect happened mid-unwind): fall back to a {@code MARKET} order where the symbol advertises
 * one (sweeps the book unconditionally, no price needed); where it does not (confirmed live:
 * {@code ETHUSDC}/{@code SOLUSDC}/{@code XRPUSDC} advertise only {@code LIMIT}/{@code LIMIT_MAKER}),
 * the position is left stranded, journaled, and flagged as needing IMMEDIATE operator review — never
 * submit a knowingly-doomed limit order, and never fabricate a recovery number.
 *
 * <p><b>A {@code MARKET} order is sized in the asset you are SPENDING, which is not the same
 * parameter for both directions</b> (third-pass review finding). The first cut of the MARKET
 * fallback above fed a placeholder reference price of {@code 1.0} into
 * {@link CycleExecutor#quantizeLeg} so it could reuse the priced path's sizing. That is a no-op for
 * a reversal that SELLs (the held amount is already base-denominated, and dividing by 1.0 leaves it
 * alone) but a currency error for one that BUYs: the held amount is then in the QUOTE asset, and
 * dividing it by a fabricated 1.0 submits a quote amount verbatim as a base {@code quantity}. On
 * {@code usdt-btc-usdc-fwd} (leg 1 {@code BTCUSDC:BID}) that is ~100 USDC in hand becoming
 * {@code MARKET BUY 100 BTC} — the exact class of order {@code UnwinderTest} was written to prevent,
 * reachable whenever the books have been reset (which {@code MexcWsClient} does on EVERY disconnect,
 * a plausible correlated cause of the leg failure that triggered the unwind in the first place).
 * A reversal that BUYs is now sized with {@code quoteOrderQty} — the quote amount actually held —
 * and never carries a {@code quantity} at all.
 *
 * <p><b>{@code quoteOrderQty} is UNVERIFIED against this venue</b>, in the same sense as
 * {@code cf-bot.exec.order-type=IOC} (see {@code BotConfig.ExecConfig#orderType}): it is documented
 * for {@code POST /api/v3/order} in the Binance-family spot v3 contract MEXC's API is modeled on,
 * but this project has not put a credentialed request through it. It fails SAFE if unsupported — a
 * 4xx rejection classifies as {@code REJECTED_PRESUBMIT}, which reports the position as stranded for
 * operator review, rather than submitting a wrongly-sized order. The credentialed 1-USDT probe
 * (cf-arb-bot-plan.md's own release gate) must exercise this path before {@code dry-run=false}.
 */
public final class Unwinder {

    private static final Logger LOG = Logger.getLogger(Unwinder.class);

    private final OrderReconciler reconciler;
    private final BookRegistry books;
    private final long unwindCrossBps;

    public Unwinder(MexcOrderApi rest, String orderType, long legTimeoutMs, BookRegistry books,
                     long unwindCrossBps) {
        this.reconciler = new OrderReconciler(rest, orderType, legTimeoutMs);
        this.books = books;
        this.unwindCrossBps = unwindCrossBps;
    }

    /** Result of an unwind attempt. {@code recoveredAnchorFixed} is always anchor-denominated.
     * {@code unrecoverable} (REVIEW.md MAJ-02's fallback ladder) is true only when NO pricing source
     * existed for a required reversal and the symbol offers no {@code MARKET} fallback either — real
     * inventory is verifiably left in an unconverted asset with no automated path back, and the
     * caller must treat this as an immediate operator-review emergency, not an ordinary broken cycle. */
    public record Result(boolean anyOrderPlaced, long recoveredAnchorFixed, String detail, boolean unrecoverable) {
    }

    public Result unwind(Triangle triangle, CycleState state, int failedLegIndex) {
        int heldLegIndex = -1;
        for (int i = failedLegIndex; i >= 0; i--) {
            CycleState.LegStatus status = state.legs[i].status;
            if (status == CycleState.LegStatus.FILLED || status == CycleState.LegStatus.PARTIAL) {
                heldLegIndex = i;
                break;
            }
        }

        if (heldLegIndex < 0) {
            return new Result(false, 0, "nothing acquired -- no unwind needed", false);
        }

        Side[] sides = triangle.side();
        SymbolFilter[] filters = triangle.filter();

        // Anchor already in hand and needing no reversal (only leg 2 can produce this -- its output
        // IS the anchor by the triangle-closure invariant), kept separate from `working`, which is
        // whatever non-anchor asset the walk is currently carrying backwards.
        long anchorRecovered = 0;
        long working = OrderReconciler.netProceeds(sides[heldLegIndex], filters[heldLegIndex],
                state.legs[heldLegIndex]);
        boolean anyOrderPlaced = false;

        for (int i = heldLegIndex; i >= 0; i--) {
            Side reverseSide = sides[i] == Side.BID ? Side.ASK : Side.BID;
            SymbolFilter filter = filters[i];

            if (i == 2) {
                // Leg 2 always returns to the anchor by construction (triangle-closure validation in
                // TriangleRegistry) -- its own proceeds ARE recovered anchor; nothing to reverse.
                // The walk still continues down through legs 1 and 0, because a leg-2 PARTIAL leaves
                // an UNSOLD remainder of leg 1's output behind (see residualHeldBy below), and that
                // remainder is emphatically not anchor-denominated.
                LOG.warnf("[unwind] triangle=%s heldLeg=2 proceeds already anchor-denominated, banked=%.8f",
                        triangle.name(), FixedPoint.toDouble(working));
                anchorRecovered += working;
                working = 0;
            } else if (working > 0) {
                Result failure = reverseOneLeg(triangle, state, i, reverseSide, filter, working,
                        anchorRecovered, anyOrderPlaced);
                if (failure != null) {
                    return failure;
                }
                anyOrderPlaced = true;
                working = lastReversalProceeds;
                LOG.warnf("[unwind] triangle=%s leg=%d symbol=%s reverseSide=%s recovered=%.8f %s",
                        triangle.name(), i, filter.symbol(), reverseSide, FixedPoint.toDouble(working),
                        triangle.fromAsset()[i]);
            }

            // THIRD-PASS REVIEW FINDING: whatever leg i was handed but never actually consumed is
            // still sitting in leg i's FROM-asset -- which is exactly the asset the reversal above
            // just returned to, and exactly the asset leg i-1's reversal spends. Carry it along
            // instead of abandoning it.
            //
            // Unwinder's original model ("at most ONE leg's output is ever in hand and not yet
            // converted forward -- every earlier leg's output was already fully spent as the next
            // leg's input") is false precisely in the PARTIAL case, which is the case
            // exec.CycleExecutor deliberately routes here: a leg that filled 40% consumed only 40%
            // of what it was handed, and the other 60% sat un-reversed, un-flagged, and booked by
            // handleBrokenCycle as a 100% loss while the asset was still in the account.
            //
            // Leg 0 is excluded on purpose: its from-asset IS the anchor, so its unconsumed
            // remainder was never spent, and CycleExecutor#anchorSpent already measures the spend
            // from leg 0's ACTUAL executedQuote/executedBase. Adding it to the recovery side would
            // double-count it as profit.
            if (i >= 1) {
                working += residualHeldBy(sides[i], state.legs[i]);
            }
        }

        // After leg 0's reversal, `working` is anchor-denominated by the triangle-closure invariant
        // (fromAsset[0] == anchor, validated at startup).
        return new Result(anyOrderPlaced, anchorRecovered + working,
                "reversed legs " + heldLegIndex + "..0", false);
    }

    /** Proceeds of the reversal {@link #reverseOneLeg} last placed, in that leg's FROM-asset units.
     * An out-parameter rather than a return value so {@code reverseOneLeg} can return the
     * early-exit {@link Result} (or {@code null} to continue) -- this class is single-threaded, one
     * instance per executor thread, and an unwind runs to completion before the next one starts. */
    private long lastReversalProceeds;

    /** Size, submit and reconcile ONE reversal. Returns {@code null} on success (with the proceeds
     * in {@link #lastReversalProceeds}), or the terminal {@link Result} the caller must return --
     * which now carries {@code anchorRecovered}, the anchor this unwind has genuinely banked so far,
     * rather than a flat 0 that would under-report a real recovery as a total loss. */
    private Result reverseOneLeg(Triangle triangle, CycleState state, int i, Side reverseSide,
                                  SymbolFilter filter, long currentAmount, long anchorRecovered,
                                  boolean anyOrderPlacedSoFar) {
        int symbolIndex = triangle.symbolIndex()[i];
        Long priceFixed = priceReversal(reverseSide, filter, symbolIndex);
        boolean useMarket = priceFixed == null;
        if (useMarket && !filter.orderTypes().contains("MARKET")) {
            LOG.errorf("[unwind] triangle=%s leg=%d symbol=%s: no live book to price the reversal AND "
                            + "the venue does not offer MARKET for this symbol -- %.8f of %s is STRANDED, "
                            + "IMMEDIATE operator review required", triangle.name(), i, filter.symbol(),
                    FixedPoint.toDouble(currentAmount), triangle.fromAsset()[i]);
            return new Result(anyOrderPlacedSoFar, anchorRecovered,
                    "unrecoverable: no pricing source and no MARKET fallback reversing leg " + i
                            + " on " + filter.symbol(), true);
        }

        // Sizing. A MARKET order carries no price, so it is sized purely in the asset being SPENT:
        // base `quantity` when selling, quote `quoteOrderQty` when buying (see the class javadoc --
        // conflating the two is what produced a 100-BTC market buy). A priced reversal goes through
        // the same quantization the forward legs use.
        long baseQty = 0;
        long quoteQty = 0;
        boolean belowMinimum;
        if (useMarket && reverseSide == Side.ASK) {
            quoteQty = truncateToDecimals(currentAmount, filter.priceDecimals());
            belowMinimum = quoteQty < filter.minNotional();
        } else if (useMarket) {
            baseQty = FixedPoint.quantizeDown(currentAmount, filter.qtyStep());
            belowMinimum = baseQty < filter.minQty();
        } else {
            long[] q = CycleExecutor.quantizeLeg(reverseSide, filter, currentAmount, priceFixed);
            baseQty = q[0];
            quoteQty = q[1];
            belowMinimum = baseQty < filter.minQty() || quoteQty < filter.minNotional();
        }
        if (belowMinimum) {
            LOG.errorf("[unwind] triangle=%s leg=%d symbol=%s: held amount %.8f of %s is below the venue "
                            + "minimum to reverse -- STRANDED, operator review required",
                    triangle.name(), i, filter.symbol(), FixedPoint.toDouble(currentAmount),
                    triangle.toAsset()[i]);
            return new Result(anyOrderPlacedSoFar, anchorRecovered,
                    "stranded below venue minimum reversing leg " + i + " on " + filter.symbol(), false);
        }

        String clientOrderId = state.legs[i].clientOrderId + "-unwind";
        CycleState.Leg reverseLeg = new CycleState.Leg(clientOrderId);
        // A quote-sized MARKET BUY has no requested BASE quantity to compare a fill against, so 0 is
        // the honest value here: OrderReconciler#classifyFill then reads any non-zero fill as FILLED
        // (a MARKET order either sweeps or is rejected -- "partially filled relative to a requested
        // base quantity" is not a state it can be in) and a zero fill as ZERO_FILL, unchanged.
        reverseLeg.requestedBaseQtyFixed = baseQty;
        reverseLeg.requestedPriceFixed = useMarket ? 0 : priceFixed;

        if (useMarket) {
            LOG.warnf("[unwind] triangle=%s leg=%d symbol=%s: no live book to price the reversal -- "
                            + "falling back to MARKET (%s)", triangle.name(), i, filter.symbol(),
                    reverseSide == Side.ASK ? "quoteOrderQty" : "quantity");
            submitMarketAndReconcile(filter, reverseSide, baseQty, quoteQty, reverseLeg);
        } else {
            reconciler.submitAndReconcile(filter.symbol(), reverseSide, filter, reverseLeg);
        }

        if (reverseLeg.status != CycleState.LegStatus.FILLED
                && reverseLeg.status != CycleState.LegStatus.PARTIAL) {
            LOG.errorf("[unwind] triangle=%s leg=%d symbol=%s reversal did not fill (status=%s) -- "
                            + "%.8f of %s remains STRANDED, operator review required",
                    triangle.name(), i, filter.symbol(), reverseLeg.status,
                    FixedPoint.toDouble(currentAmount), triangle.toAsset()[i]);
            return new Result(true, anchorRecovered,
                    "reversal did not fill on leg " + i + " (" + reverseLeg.status + ")", false);
        }

        lastReversalProceeds = OrderReconciler.netProceeds(reverseSide, filter, reverseLeg);
        return null;
    }

    /** The part of {@code leg}'s INPUT that the leg never actually consumed, in that leg's
     * from-asset units -- an ASK leg spends quote ({@code cummulativeQuoteQty}), a BID leg spends
     * base ({@code executedQty}). Non-zero for any PARTIAL fill, and dust-sized for a FILLED one
     * (the quantization slack {@code Sizer}/{@code quantizeLeg} rounds away). Clamped at 0 so a leg
     * whose {@code inputAmountFixed} was never populated degrades to the old
     * abandon-the-remainder behavior rather than fabricating a negative recovery. */
    private static long residualHeldBy(Side side, CycleState.Leg leg) {
        long consumed = side == Side.ASK ? leg.executedQuoteFixed : leg.executedBaseQtyFixed;
        return Math.max(0, leg.inputAmountFixed - consumed);
    }

    /** Cross the CURRENT top of book by {@code unwindCrossBps}, clamped inside the symbol's
     * {@code PERCENT_PRICE_BY_SIDE} band -- REVIEW.md MAJ-02. Returns {@code null} if the book has
     * no usable top on the required side (untrusted/empty, e.g. mid-reconnect), signaling the
     * caller to fall back to {@code MARKET}. */
    private Long priceReversal(Side reverseSide, SymbolFilter filter, int symbolIndex) {
        if (reverseSide == Side.ASK) {
            // BUY: cross ABOVE the current best ask.
            long topAsk = books.topAskFixed(symbolIndex);
            if (topAsk == Long.MIN_VALUE) {
                return null;
            }
            long crossed = FixedPoint.mulDiv(topAsk, FixedPoint.fromDouble(1.0 + unwindCrossBps / 10_000.0), FixedPoint.SCALE);
            return clampToPriceBand(crossed, reverseSide, filter, topAsk);
        } else {
            // SELL: cross BELOW the current best bid.
            long topBid = books.topBidFixed(symbolIndex);
            if (topBid == Long.MIN_VALUE) {
                return null;
            }
            long crossed = FixedPoint.mulDiv(topBid, FixedPoint.fromDouble(1.0 - unwindCrossBps / 10_000.0), FixedPoint.SCALE);
            return clampToPriceBand(crossed, reverseSide, filter, topBid);
        }
    }

    /** MEXC's {@code PERCENT_PRICE_BY_SIDE} filter: a BUY ("bid" side) order may not price further
     * ABOVE the reference than {@code bidMultiplierUp}; a SELL ("ask" side) order may not price
     * further BELOW it than {@code askMultiplierDown}. An absent band (not published for this
     * symbol) leaves the price unclamped -- the small, fixed cross buffer is the only guard in that
     * case, which every configured symbol currently avoids by publishing a real band. */
    static long clampToPriceBand(long price, Side reverseSide, SymbolFilter filter, long referencePriceFixed) {
        if (reverseSide == Side.ASK) {
            if (!Double.isNaN(filter.bidMultiplierUp())) {
                long cap = FixedPoint.mulDiv(referencePriceFixed,
                        FixedPoint.fromDouble(1.0 + filter.bidMultiplierUp()), FixedPoint.SCALE);
                price = Math.min(price, cap);
            }
        } else {
            if (!Double.isNaN(filter.askMultiplierDown())) {
                long floor = FixedPoint.mulDiv(referencePriceFixed,
                        FixedPoint.fromDouble(1.0 - filter.askMultiplierDown()), FixedPoint.SCALE);
                price = Math.max(price, floor);
            }
        }
        return price;
    }

    /** Truncate a 1e8-fixed amount DOWN to {@code decimals} fractional digits, so the value compared
     * against {@code minNotional} is the same one {@link FixedPoint#toPlainString} will render onto
     * the wire. Truncating down is the safe direction for a {@code quoteOrderQty}: it can only
     * spend less of the quote asset than is actually held, never more. */
    static long truncateToDecimals(long fixed, int decimals) {
        long step = 1L;
        for (int i = decimals; i < 8; i++) {
            step *= 10L;
        }
        return (fixed / step) * step;
    }

    /** A {@code MARKET} reversal, sized in the asset being SPENT: a SELL spends the base asset and
     * carries {@code quantity}; a BUY spends the quote asset and carries {@code quoteOrderQty} (and
     * must NOT carry a {@code quantity} -- see the class javadoc for the 100-BTC order that the
     * previous shared-{@code quantity} form produced). Neither carries a price. */
    private void submitMarketAndReconcile(SymbolFilter filter, Side side, long baseQtyFixed,
                                           long quoteQtyFixed, CycleState.Leg leg) {
        String sideStr = side == Side.ASK ? "BUY" : "SELL";
        String sizeParam = side == Side.ASK
                ? "&quoteOrderQty=" + FixedPoint.toPlainString(quoteQtyFixed, filter.priceDecimals())
                : "&quantity=" + FixedPoint.toPlainString(baseQtyFixed, filter.qtyDecimals());
        String params = "symbol=" + filter.symbol() + "&side=" + sideStr + "&type=MARKET"
                + sizeParam + "&newClientOrderId=" + leg.clientOrderId;
        reconciler.submitPrebuiltAndReconcile(filter.symbol(), params, leg);
    }
}

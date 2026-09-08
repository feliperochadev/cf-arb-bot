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
        long currentAmount = OrderReconciler.netProceeds(sides[heldLegIndex], filters[heldLegIndex],
                state.legs[heldLegIndex]);

        if (heldLegIndex == 2) {
            // Leg 2 always returns to the anchor by construction (triangle-closure validation in
            // TriangleRegistry) -- its own proceeds ARE the recovered anchor amount; nothing to
            // reverse.
            LOG.warnf("[unwind] triangle=%s heldLeg=2 already anchor-denominated, recoveredAnchor=%.8f",
                    triangle.name(), FixedPoint.toDouble(currentAmount));
            return new Result(false, currentAmount, "leg 2 proceeds already anchor-denominated", false);
        }

        boolean anyOrderPlaced = false;
        for (int i = heldLegIndex; i >= 0; i--) {
            Side reverseSide = sides[i] == Side.BID ? Side.ASK : Side.BID;
            SymbolFilter filter = filters[i];
            int symbolIndex = triangle.symbolIndex()[i];

            Long priceFixed = priceReversal(reverseSide, filter, symbolIndex);
            boolean useMarket = priceFixed == null;
            if (useMarket && !filter.orderTypes().contains("MARKET")) {
                LOG.errorf("[unwind] triangle=%s leg=%d symbol=%s: no live book to price the reversal AND "
                                + "the venue does not offer MARKET for this symbol -- %.8f of %s is STRANDED, "
                                + "IMMEDIATE operator review required", triangle.name(), i, filter.symbol(),
                        FixedPoint.toDouble(currentAmount), triangle.fromAsset()[i]);
                return new Result(anyOrderPlaced, 0,
                        "unrecoverable: no pricing source and no MARKET fallback reversing leg " + i
                                + " on " + filter.symbol(), true);
            }

            // Reference price for quantization only when we have one; a MARKET order needs no price
            // at all, so a missing top-of-book does not block sizing the reversal via MARKET.
            long referencePriceFixed = priceFixed != null ? priceFixed : lastResortReferencePrice(filter);
            long[] q = CycleExecutor.quantizeLeg(reverseSide, filter, currentAmount, referencePriceFixed);
            long baseQty = q[0];
            long quoteAmt = q[1];
            // useMarket's quoteAmt was computed against lastResortReferencePrice's placeholder 1.0,
            // not a real price -- it carries no information about the ACTUAL notional and must never
            // be compared against filter.minNotional() (a real quote-currency threshold), or a
            // recoverable position gets wrongly stranded on a units artifact. minQty is denominated
            // in the base asset and unaffected by price, so it still applies; the real minNotional
            // (if genuinely violated) surfaces as a venue rejection during reconciliation instead.
            boolean belowMinimum = baseQty < filter.minQty() || (!useMarket && quoteAmt < filter.minNotional());
            if (belowMinimum) {
                LOG.errorf("[unwind] triangle=%s leg=%d symbol=%s: held amount %.8f is below the venue "
                                + "minimum to reverse -- STRANDED, operator review required",
                        triangle.name(), i, filter.symbol(), FixedPoint.toDouble(currentAmount));
                return new Result(anyOrderPlaced, 0,
                        "stranded below venue minimum reversing leg " + i + " on " + filter.symbol(), false);
            }

            String clientOrderId = state.legs[i].clientOrderId + "-unwind";
            CycleState.Leg reverseLeg = new CycleState.Leg(clientOrderId);
            reverseLeg.requestedBaseQtyFixed = baseQty;
            reverseLeg.requestedPriceFixed = useMarket ? 0 : priceFixed;

            if (useMarket) {
                LOG.warnf("[unwind] triangle=%s leg=%d symbol=%s: no live book to price the reversal -- "
                        + "falling back to MARKET", triangle.name(), i, filter.symbol());
                submitMarketAndReconcile(filter, reverseSide, baseQty, reverseLeg);
            } else {
                reconciler.submitAndReconcile(filter.symbol(), reverseSide, filter, reverseLeg);
            }
            anyOrderPlaced = true;

            if (reverseLeg.status != CycleState.LegStatus.FILLED
                    && reverseLeg.status != CycleState.LegStatus.PARTIAL) {
                LOG.errorf("[unwind] triangle=%s leg=%d symbol=%s reversal did not fill (status=%s) -- "
                                + "%.8f of %s remains STRANDED, operator review required",
                        triangle.name(), i, filter.symbol(), reverseLeg.status,
                        FixedPoint.toDouble(currentAmount), triangle.toAsset()[i]);
                return new Result(anyOrderPlaced, 0,
                        "reversal did not fill on leg " + i + " (" + reverseLeg.status + ")", false);
            }

            currentAmount = OrderReconciler.netProceeds(reverseSide, filter, reverseLeg);
            LOG.warnf("[unwind] triangle=%s leg=%d symbol=%s reverseSide=%s recovered=%.8f %s",
                    triangle.name(), i, filter.symbol(), reverseSide, FixedPoint.toDouble(currentAmount),
                    triangle.fromAsset()[i]);
        }

        // After leg 0's reversal, currentAmount is anchor-denominated by the triangle-closure
        // invariant (fromAsset[0] == anchor, validated at startup).
        return new Result(anyOrderPlaced, currentAmount, "reversed legs " + heldLegIndex + "..0", false);
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

    /** Only reached when a MARKET fallback is about to be used purely to QUANTIZE the base quantity
     * (a MARKET order itself carries no price) -- any recent price is fine here since it never
     * reaches the venue; falls back to 1.0 (a harmless quantization unit) if truly nothing is known. */
    private static long lastResortReferencePrice(SymbolFilter filter) {
        return FixedPoint.SCALE; // 1.0 in 1e8-fixed -- see method javadoc: not sent to the venue
    }

    private void submitMarketAndReconcile(SymbolFilter filter, Side side, long baseQtyFixed, CycleState.Leg leg) {
        String sideStr = side == Side.ASK ? "BUY" : "SELL";
        String qtyStr = FixedPoint.toPlainString(baseQtyFixed, filter.qtyDecimals());
        String params = "symbol=" + filter.symbol() + "&side=" + sideStr + "&type=MARKET"
                + "&quantity=" + qtyStr + "&newClientOrderId=" + leg.clientOrderId;
        reconciler.submitPrebuiltAndReconcile(filter.symbol(), params, leg);
    }
}

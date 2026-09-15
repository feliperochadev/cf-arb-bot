package io.cfarb.strategy;

import io.cfarb.book.BookRegistry;
import io.cfarb.book.L2Book;
import io.cfarb.graph.Triangle;
import io.cfarb.model.Side;
import io.cfarb.model.SymbolFilter;
import io.cfarb.util.FixedPoint;

/**
 * Computes the ACTUAL achievable net edge of a triangle at a given starting capital — walking each
 * leg's real ladder, quantizing to the exchange's real lot size, and applying the real per-symbol
 * taker fee (cf-arb-bot-plan.md §5.3). This is the single piece the plan calls out as needing to be
 * "exactly right": untradeable triangles (e.g. anything sized through {@code SOLBTC} at a $100
 * seed) fall out of this arithmetic naturally — no cycle needs to be blacklisted by hand, and no
 * separate "is this even fillable" pre-check is needed, because {@link Sizer#fillLeg} already
 * rejects unfillable legs.
 *
 * <p>Cross-checked against the Python research pipeline's {@code cfarb.stage2_cycles.evaluate_cycle}
 * model in {@code EdgeCalculatorTest} using shared synthetic book fixtures (cf-arb-bot-plan.md §11
 * verification item 3) — both models must agree on {@code net_bps} to within rounding for the same
 * inputs, which is what makes this bot's live opportunity detection provably the same model the
 * $100 → $607.91 (later found unreachable — see cf-arb-bot-plan.md §2.1) and $100 → $224.45 headline
 * numbers came from. <b>DYNAMIC-SIZING-TASK.md non-negotiable #7 note:</b> the fixed-size
 * {@link #evaluate(Triangle, BookRegistry, long, Result)} 4-arg entry point the cross-check calls is
 * NEVER modified by the gross early-out or the sizing search below — both are additive entry points
 * ({@link #evaluate(Triangle, BookRegistry, long, Result, double)}, {@link #evaluateBestSize}) that
 * delegate into the same gross/pass-2 arithmetic, so the cross-checked numbers cannot drift.
 *
 * <p>Zero allocation on the hot path: {@link Result} and the internal {@link Sizer.Result} are
 * reusable output parameters, allocated once per {@code OpportunityDetector} instance and passed by
 * reference (rule R1).
 *
 * <p><b>REVIEW.md MED-03</b> ("Sizer.fillAsk overstates quote notional on multi-level walks"):
 * verified NOT a defect. {@code Sizer.Result#quoteFixed} is {@code baseFilled x worstPrice}, which
 * is exactly the notional MEXC's own min-notional filter evaluates for a limit order priced at that
 * boundary -- it is the correct value for its one real use (Sizer's own {@code minNotional} check).
 * The finding also claimed downstream modules "misstate the expected spend" from this value; in
 * fact nothing downstream ever read it -- {@link Result} previously carried a {@code legQuoteFixed[]}
 * array populated from this same field and never consumed anywhere. Removed as dead code rather
 * than "fixing" an already-correct number.
 */
public final class EdgeCalculator {

    private final Sizer.Result legResult = new Sizer.Result();

    // PRE-LIVE-PLAN.md P0-1: nullable, dry-run-only -- see ConsumptionLedger's own javadoc. Threaded
    // into walkPass2 ONLY (never the gross first pass, which is size-independent and reads only
    // top-of-book). null by default (live mode, or dry-run with the feature disabled); set once by
    // BotService via setConsumptionLedger, never touched by the 9 existing `new EdgeCalculator()`
    // call sites this constructor's shape deliberately preserves.
    private ConsumptionLedger consumptionLedger;

    // DYNAMIC-SIZING-TASK.md Phase 2: preallocated scratch for evaluateBestSize's ladder-boundary
    // search -- one instance per EdgeCalculator (one per OpportunityDetector), never per evaluation
    // (rule R1). candidateScratch is sized to Sizer.candidateInputs' own 8-slot bound.
    private final long[] candidateScratch = new long[8];
    private final Result sizingScratch = new Result();

    /** PRE-LIVE-PLAN.md P0-1: install the consumption ledger this instance's {@code walkPass2}
     * threads into {@link Sizer#fillLeg}. Called once by {@code BotService} (dry-run only); every
     * other caller/test leaves this {@code null}, reproducing pre-P0-1 behaviour exactly. */
    public void setConsumptionLedger(ConsumptionLedger ledger) {
        this.consumptionLedger = ledger;
    }

    /** Reusable output of one full-triangle evaluation. */
    public static final class Result {
        public boolean fillable;
        /** Final amount in the anchor asset's native units after all 3 legs, fees, and quantization. */
        public long finalAmount;
        /** (finalAmount / startAmount - 1) * 10_000, or Double.NaN if unfillable. */
        public double netBps;
        /** Top-of-book cyclic edge in bps BEFORE any depth limit or lot-size quantization:
         * {@code (Π (legRate_i * (1 - fee_i)) - 1) * 10_000}, the exact quantity the Python research
         * pipeline's {@code cfarb.stage2_cycles.evaluate_cycle} reports as its per-tick {@code net_bps}
         * ("opportunity" gate). Populated whenever all three books are usable — including when
         * {@link #fillable} is {@code false}, so a dry-run journal line distinguishes "no edge" from
         * "edge present but un-fillable at this size". {@code Double.NaN} if any leg's book was
         * untrusted / empty / crossed. */
        public double grossBps;
        /** DYNAMIC-SIZING-TASK.md Phase 1: true iff this evaluation bailed out immediately after the
         * gross-edge first pass because {@code grossBps} could not possibly clear the caller's gate
         * ({@code net ≤ gross} always — see the "gross early-out" note on {@code evaluate}'s 5-arg
         * overload) — no candidate size could have produced a fillable, above-threshold result, so
         * the expensive per-leg ladder walk never ran. {@link #fillable} stays {@code false} and
         * {@link #netBps} stays {@code NaN}; {@link #grossBps} and the T4 leg-top/touch arrays are
         * still populated (the gross-edge pass always runs). Reset to {@code false} at the top of
         * every evaluation. */
        public boolean bailedEarly;
        /** Per-leg intended amount INTO that leg (native units of that leg's from_asset) and the
         * worst ladder price the VWAP walk touched -- exec.CycleExecutor uses these to place each
         * leg's IOC order at exactly the price boundary this calculation assumed (see
         * Sizer.Result#worstPriceFixed's javadoc). Index 0..2 = leg 0..2. */
        public final long[] legInputAmount = new long[3];
        public final long[] legWorstPriceFixed = new long[3];
        /** Per-leg quantized BASE order quantity Sizer actually computed (1e8-fixed) --
         * cf-arb-bot-review-plan.md Tier 1 step 1.1: exec.CycleExecutor must submit exactly this,
         * not re-derive a (smaller) quantity from budget/worstPrice. */
        public final long[] legBaseQtyFixed = new long[3];
        /** JOURNAL-TUNING-TASK.md T4: per-leg top-of-book price (the traded side -- ask price for an
         * ASK leg, bid price for a BID leg) and the quantity available AT that touch, 1e8-fixed.
         * Populated in the gross-edge first pass whenever all three books are usable, so an
         * {@code unfillable} (or, since DYNAMIC-SIZING-TASK.md Phase 1, a {@code bailedEarly})
         * journal line still carries them. Index 0..2 = leg 0..2. */
        public final long[] legTopPriceFixed = new long[3];
        public final long[] legTouchQtyFixed = new long[3];
        /** DUPLICATE-FIRE-TASK.md ("Fix A"): per-leg max {@link io.cfarb.book.L2Book} write stamp
         * across every ladder level the VWAP walk consumed ({@link Sizer.Result#maxWriteSeq}).
         * {@code OpportunityDetector} stores {@code (legWorstPriceFixed, legBaseQtyFixed,
         * legWriteSeq)} per leg on a fire and suppresses a re-fire while all three are unchanged for
         * every leg -- the write stamp is what distinguishes "nobody touched this level" from "it
         * was consumed and replenished". Only populated in the fillable second pass. Index 0..2. */
        public final long[] legWriteSeq = new long[3];
        /** PRE-LIVE-PLAN.md P0-1: per-leg copy of {@link Sizer.Result}'s per-level consumption
         * breakdown -- flat {@code [leg*Sizer.MAX_TRACKED_LEVELS + level]}, valid indices
         * {@code 0..legLevelsTouched[leg]-1}. Only populated when a fillable candidate was walked
         * WITH a non-null ledger; {@code OpportunityDetector} reads this (for the WINNING candidate
         * only) to commit claims into the ledger on an actual fire. Never read on the live path. */
        public final int[] legLevelsTouched = new int[3];
        public final int[] legLevelIndex = new int[3 * Sizer.MAX_TRACKED_LEVELS];
        public final long[] legLevelQtyConsumed = new long[3 * Sizer.MAX_TRACKED_LEVELS];
        public final long[] legLevelWriteSeq = new long[3 * Sizer.MAX_TRACKED_LEVELS];
        /** DYNAMIC-SIZING-TASK.md Phase 2: how many ladder-boundary candidate sizes
         * {@link #evaluateBestSize} actually walked through pass 2 -- {@code 0} if the evaluation
         * bailed early, or if this {@code Result} was produced by the plain fixed-size
         * {@link #evaluate}. Journaled as {@code size_candidates}. */
        public int sizeCandidates;
        /** DYNAMIC-SIZING-TASK.md Phase 2: the {@code netBps} sizing at the FULL {@code
         * maxStartAmount} (the operator's cap) would have produced -- exactly what the OLD
         * fixed-size {@code evaluate(..., maxStartAmount, ...)} path would have fired at.
         * {@code Double.NaN} if the evaluation bailed early, if the cap itself was unfillable, or if
         * this {@code Result} was produced by the plain fixed-size {@link #evaluate}. This is the
         * A/B measurement for "optimising dollars beats optimising bps"
         * (JOURNAL-BPS-ANALYSIS.md §14.1): comparing it against {@link #netBps} on the same journal
         * line proves the gain from a live capture rather than a two-point model fit. Journaled as
         * {@code net_bps_at_cap}. */
        public double netBpsAtCap;
    }

    private static void resetResult(Result out) {
        out.fillable = false;
        out.finalAmount = 0;
        out.netBps = Double.NaN;
        out.grossBps = Double.NaN;
        out.bailedEarly = false;
        out.sizeCandidates = 0;
        out.netBpsAtCap = Double.NaN;
        // JOURNAL-TUNING-TASK.md T4: Result is reused across evaluations -- zero every per-leg array
        // so a journal line for an unfillable/bailed candidate (whose second pass never runs, or
        // returns mid-loop) never carries a stale leg's data from a previous evaluation.
        java.util.Arrays.fill(out.legInputAmount, 0L);
        java.util.Arrays.fill(out.legWorstPriceFixed, 0L);
        java.util.Arrays.fill(out.legBaseQtyFixed, 0L);
        java.util.Arrays.fill(out.legTopPriceFixed, 0L);
        java.util.Arrays.fill(out.legTouchQtyFixed, 0L);
        java.util.Arrays.fill(out.legWriteSeq, 0L);
        java.util.Arrays.fill(out.legLevelsTouched, 0);
    }

    /**
     * Evaluate {@code triangle} starting from {@code startAmount} units (1e8-fixed) of the anchor
     * asset. Every leg must independently clear its exchange minimum and have enough displayed
     * depth to absorb the FULL intended size (Sizer's stricter-than-backtest policy) or the whole
     * triangle is marked unfillable — a triangle is only as good as its worst leg.
     *
     * <p>Delegates to the 5-arg overload with {@code bailBelowGrossBps = Double.NEGATIVE_INFINITY}
     * — i.e. never bails early. Behaviour is byte-for-byte unchanged from before
     * DYNAMIC-SIZING-TASK.md Phase 1: this is the entry point {@code EdgeCalculatorTest}'s Python
     * cross-check calls, and it must keep agreeing with {@code cfarb.stage2_cycles.evaluate_cycle}.
     *
     * <p><b>PRE-LIVE-PLAN.md P0-1 / non-negotiable #7:</b> this entry point (and the 5-arg one below)
     * ALWAYS walks with a {@code null} consumption ledger, unconditionally — even on an instance
     * that has one installed via {@link #setConsumptionLedger} for {@link #evaluateBestSize}'s use.
     * No production caller ever does that (only {@code evaluateBestSize} is wired to a ledger), but
     * the guarantee is unconditional on purpose: "byte-for-byte unchanged" must not depend on caller
     * discipline.
     */
    public void evaluate(Triangle triangle, BookRegistry books, long startAmount, Result out) {
        evaluate(triangle, books, startAmount, out, Double.NEGATIVE_INFINITY);
    }

    /**
     * DYNAMIC-SIZING-TASK.md Phase 1 — gross early-out. {@code net_bps ≤ gross_bps} always: the
     * ladder walk starts at top-of-book and only moves to worse prices, and quantization only
     * floors, so {@code drag = gross − net ≥ 0} structurally (confirmed empirically over 41,839
     * paired journal samples — 0 had {@code net > gross}). Therefore
     * {@code grossBps ≤ bailBelowGrossBps} means no size can clear the caller's gate, and the
     * (expensive) per-leg ladder walk is skipped entirely — this is dead-code elimination at
     * runtime, not a heuristic. Measured over 7.92M evaluations: 99.54% could not possibly have
     * cleared the gate.
     *
     * <p>Pass {@code Double.NEGATIVE_INFINITY} to never bail (identical to the 4-arg overload).
     * {@code OpportunityDetector} forces this once per triangle per reject-sample window so
     * JOURNAL-BPS-ANALYSIS.md §12's paired {@code (gross_bps, net_bps)} drag diagnostic keeps
     * appearing on the journal even though most ticks now skip the walk.
     */
    public void evaluate(Triangle triangle, BookRegistry books, long startAmount, Result out,
                         double bailBelowGrossBps) {
        resetResult(out);
        if (!computeGrossFirstPass(triangle, books, out)) {
            return; // a leg's book isn't usable -- never fabricate an edge (S5)
        }
        if (out.grossBps <= bailBelowGrossBps) {
            out.bailedEarly = true;
            return; // fillable stays false, netBps stays NaN -- see bailedEarly's javadoc
        }
        // PRE-LIVE-PLAN.md P0-1 / non-negotiable #7: this entry point (and the 4-arg one above) is
        // NEVER wired to a consumption ledger by any caller -- consumptionLedger stays whatever this
        // instance's field is (null for every EdgeCalculatorTest / cross-check use), so nowNanos
        // (0L) is never actually read by Sizer. Byte-for-byte unchanged from before P0-1.
        walkPass2(triangle, books, startAmount, out, null, 0L);
    }

    /**
     * DYNAMIC-SIZING-TASK.md Phase 2 — dynamic per-tick sizing. Rather than asking "is this
     * fixed size good?", enumerates the ladder-level-boundary sizes up to {@code maxStartAmount}
     * (the operator's cap, itself unchanged -- S6) and picks the one maximising ABSOLUTE profit
     * ({@code finalAmount - startAmount}), not bps: {@code profit(N) = finalAmount(N) - N} is
     * concave in the depth dimension (deeper levels price worse), so its maximum sits at a level
     * boundary and enumerating those boundaries finds it (JOURNAL-BPS-ANALYSIS.md §14.1: ~2.8x
     * more profit per trade than pinning every fire to the cap).
     *
     * <p><b>Risk properties</b> (kept as code comments per the task, not just the doc):
     * <ul>
     *   <li><b>Can only size DOWN</b> from {@code maxStartAmount} — every candidate
     *       {@link Sizer#candidateInputs} produces is {@code <= maxStartAmount}, so the S6 ceiling
     *       an operator configured is never exceeded, only potentially under-used.</li>
     *   <li><b>Fill-in-full preserved (#10):</b> every candidate is still evaluated through
     *       {@link Sizer#fillLeg}, which rejects any size the displayed ladder cannot absorb in
     *       full. This picks among fully-fillable sizes; it never partial-fills.</li>
     *   <li><b>Fails closed:</b> no fillable candidate anywhere in the search →
     *       {@code out.fillable = false} → no fire.</li>
     *   <li><b>Bounded work:</b> at most 8 candidates x 3 legs, and only on the tiny fraction of
     *       ticks that clear the gross ceiling (Phase 1 is what makes this affordable).</li>
     * </ul>
     */
    public void evaluateBestSize(Triangle triangle, BookRegistry books, long maxStartAmount, Result out,
                                 double bailBelowGrossBps, long nowNanos) {
        resetResult(out);
        if (!computeGrossFirstPass(triangle, books, out)) {
            return;
        }
        // Gross is size-independent: if the BEST possible size (maxStartAmount) can't clear the
        // gate, no smaller candidate can either -- this is what makes the search affordable.
        if (out.grossBps <= bailBelowGrossBps) {
            out.bailedEarly = true;
            return;
        }

        int[] symbolIndex = triangle.symbolIndex();
        Side[] sides = triangle.side();
        L2Book book0 = books.book(symbolIndex[0]);
        int k = Sizer.candidateInputs(book0, sides[0], maxStartAmount, candidateScratch);
        out.sizeCandidates = k;

        boolean any = false;
        long bestProfit = 0L;
        for (int i = 0; i < k; i++) {
            long candidate = candidateScratch[i];
            boolean filled = walkPass2(triangle, books, candidate, sizingScratch, consumptionLedger, nowNanos);
            if (i == k - 1) {
                // Sizer.candidateInputs guarantees maxStartAmount is always the LAST candidate --
                // this is exactly what the old fixed-size evaluate(..., maxStartAmount, ...) would
                // have produced. NaN (sizingScratch's reset value) if unfillable at the cap.
                out.netBpsAtCap = sizingScratch.netBps;
            }
            if (!filled) {
                continue;
            }
            long profit = sizingScratch.finalAmount - candidate;
            if (!any || profit > bestProfit) {
                any = true;
                bestProfit = profit;
                copyPass2Result(sizingScratch, out);
            }
        }
        // out.fillable is already false (from resetResult) if no candidate filled.
    }

    /** The gross-edge first pass: top-of-book cyclic edge, no depth limit, no quantization -- just
     * the log-space rate product cf-arb-poc's stage2_cycles.evaluate_cycle uses. Cheap (3
     * divisions, zero allocation), size-independent, shared by every entry point above. Populates
     * {@code out.grossBps} and the T4 leg-top/touch arrays; returns {@code false} (leaving
     * {@code grossBps} NaN) if any leg's book isn't usable -- caller's staleness/health gates should
     * already have screened this, but this method must never fabricate an edge from a book that
     * isn't ready. */
    private static boolean computeGrossFirstPass(Triangle triangle, BookRegistry books, Result out) {
        int[] symbolIndex = triangle.symbolIndex();
        Side[] sides = triangle.side();
        SymbolFilter[] filters = triangle.filter();

        double grossProduct = 1.0;
        for (int leg = 0; leg < 3; leg++) {
            L2Book book = books.book(symbolIndex[leg]);
            if (!book.isTrusted() || book.isCrossed() || book.isEmpty()) {
                return false;
            }
            long topPxFixed = sides[leg] == Side.ASK ? book.bestAskPx() : book.bestBidPx();
            out.legTopPriceFixed[leg] = topPxFixed;
            out.legTouchQtyFixed[leg] = sides[leg] == Side.ASK ? book.bestAskQty() : book.bestBidQty();
            double px = FixedPoint.toDouble(topPxFixed);
            double legRate = (sides[leg] == Side.ASK ? 1.0 / px : px)
                    * FixedPoint.toDouble(filters[leg].takerFeeMultiplierFixed());
            grossProduct *= legRate;
        }
        out.grossBps = (grossProduct - 1.0) * 10_000.0;
        return true;
    }

    /** The real achievable edge: VWAP-walk each leg's ladder to {@code startAmount}, quantize to
     * the exchange lot step, apply fees. Writes {@code fillable}/{@code finalAmount}/{@code netBps}
     * and the per-leg input/worst-price/base-qty/write-seq arrays into {@code out} (which may be the
     * real output {@code Result} for {@link #evaluate}, or the shared {@link #sizingScratch} for one
     * candidate of {@link #evaluateBestSize}'s search). Returns whether every leg filled. */
    private boolean walkPass2(Triangle triangle, BookRegistry books, long startAmount, Result out,
                               ConsumptionLedger ledger, long nowNanos) {
        out.fillable = false;
        out.finalAmount = 0;
        out.netBps = Double.NaN;
        java.util.Arrays.fill(out.legInputAmount, 0L);
        java.util.Arrays.fill(out.legWorstPriceFixed, 0L);
        java.util.Arrays.fill(out.legBaseQtyFixed, 0L);
        java.util.Arrays.fill(out.legWriteSeq, 0L);
        java.util.Arrays.fill(out.legLevelsTouched, 0);

        int[] symbolIndex = triangle.symbolIndex();
        Side[] sides = triangle.side();
        SymbolFilter[] filters = triangle.filter();

        long amount = startAmount;
        for (int leg = 0; leg < 3; leg++) {
            L2Book book = books.book(symbolIndex[leg]);
            out.legInputAmount[leg] = amount;
            Sizer.fillLeg(book, sides[leg], filters[leg], amount, legResult,
                    ledger, symbolIndex[leg], nowNanos);
            if (!legResult.filled) {
                return false; // this leg is unfillable at this size — e.g. below SOLBTC's 1-SOL minimum
            }
            out.legWorstPriceFixed[leg] = legResult.worstPriceFixed;
            out.legBaseQtyFixed[leg] = legResult.baseQtyFixed;
            out.legWriteSeq[leg] = legResult.maxWriteSeq;
            if (ledger != null) {
                // PRE-LIVE-PLAN.md P0-1: copy this leg's per-level breakdown into the leg-indexed
                // slice of `out` -- only ever read back by OpportunityDetector on an actual fire.
                int base = leg * Sizer.MAX_TRACKED_LEVELS;
                int touched = legResult.levelsTouched;
                out.legLevelsTouched[leg] = touched;
                System.arraycopy(legResult.levelIndex, 0, out.legLevelIndex, base, touched);
                System.arraycopy(legResult.levelQtyConsumed, 0, out.legLevelQtyConsumed, base, touched);
                System.arraycopy(legResult.levelWriteSeq, 0, out.legLevelWriteSeq, base, touched);
            }
            amount = legResult.outputAmount;
        }

        out.fillable = true;
        out.finalAmount = amount;
        out.netBps = (FixedPoint.toDouble(amount) / FixedPoint.toDouble(startAmount) - 1.0) * 10_000.0;
        return true;
    }

    /** Copies the pass-2-specific fields of a winning candidate from {@code src} (the search's
     * scratch {@code Result}) into {@code dst} (the real output) -- never touches {@code dst}'s
     * gross/T4/bailedEarly/sizeCandidates/netBpsAtCap fields, which the caller already owns. */
    private static void copyPass2Result(Result src, Result dst) {
        dst.fillable = true;
        dst.finalAmount = src.finalAmount;
        dst.netBps = src.netBps;
        System.arraycopy(src.legInputAmount, 0, dst.legInputAmount, 0, 3);
        System.arraycopy(src.legWorstPriceFixed, 0, dst.legWorstPriceFixed, 0, 3);
        System.arraycopy(src.legBaseQtyFixed, 0, dst.legBaseQtyFixed, 0, 3);
        System.arraycopy(src.legWriteSeq, 0, dst.legWriteSeq, 0, 3);
        // PRE-LIVE-PLAN.md P0-1: carry the winning candidate's per-level consumption breakdown too --
        // only meaningful when a ledger was in play (both arrays are all-zero/untouched otherwise).
        System.arraycopy(src.legLevelsTouched, 0, dst.legLevelsTouched, 0, 3);
        System.arraycopy(src.legLevelIndex, 0, dst.legLevelIndex, 0, 3 * Sizer.MAX_TRACKED_LEVELS);
        System.arraycopy(src.legLevelQtyConsumed, 0, dst.legLevelQtyConsumed, 0, 3 * Sizer.MAX_TRACKED_LEVELS);
        System.arraycopy(src.legLevelWriteSeq, 0, dst.legLevelWriteSeq, 0, 3 * Sizer.MAX_TRACKED_LEVELS);
    }
}

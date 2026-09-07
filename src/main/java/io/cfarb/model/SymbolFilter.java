package io.cfarb.model;

import java.util.Set;

/**
 * MEXC's per-symbol lot-size / minimum-notional filters, in 1e8 fixed-point units
 * ({@link io.cfarb.util.FixedPoint}). This is the exact data cf-arb-bot-plan.md §2.1 found missing
 * from the original Python simulation — a $100 seed cannot place a single {@code SOLBTC} order
 * because its {@code minQty} is 1 whole SOL — and §5.3 requires putting this arithmetic INSIDE the
 * edge calculation rather than filtering cycles by an estimate.
 *
 * Sourced from MEXC's PUBLIC {@code GET /api/v3/exchangeInfo} (no API key required — confirmed live
 * 2026-09-07, cf-arb-bot-plan.md §3 Gate 0 step 2/3) and mirrored in
 * {@code cf-arb-poc/config/mexc_filters.json} so the Python re-baseline and this Java bot quantize
 * identically. {@code takerBps} here is the CURRENT live per-symbol commission and supersedes
 * {@code cf-arb-poc/config/fees.yaml}'s promotional-tier assumptions, which Gate 0 found already
 * disagree with the live schedule.
 *
 * <p>{@code priceDecimals}/{@code orderTypes} were added by cf-arb-bot-review-plan.md Tier 1 step
 * 1.3 — the independent review's Major finding that {@code exec.CycleExecutor} rendered order
 * parameters as unbounded {@code double}s and hard-coded a {@code type=IOC} value the venue's own
 * per-symbol capability data never advertised. {@code orderTypes} is exactly what
 * {@code GET /api/v3/exchangeInfo} returns for the symbol — {@code exec.CycleExecutor}'s configured
 * order type must appear in it, checked at startup (fail closed, security rule S5), before live
 * mode is allowed to start.
 */
public record SymbolFilter(
        String symbol,
        String baseAsset,
        String quoteAsset,
        /** Quantization step for the base-asset quantity: 10^-baseAssetPrecision, in 1e8-fixed units. */
        long qtyStep,
        /** Decimal digits of base-asset quantity precision — how many fractional digits
         * {@link io.cfarb.util.FixedPoint#toPlainString} must render a quantity to for this symbol. */
        int qtyDecimals,
        /** Minimum order quantity (MEXC's baseSizePrecision), in 1e8-fixed base-asset units. */
        long minQty,
        /** Minimum order notional (MEXC's quoteAmountPrecision), in 1e8-fixed quote-asset units. */
        long minNotional,
        /** Decimal digits of quote-asset price precision (MEXC's quoteAssetPrecision) — how many
         * fractional digits a limit price must be rendered to for this symbol. */
        int priceDecimals,
        /** Live taker commission in basis points — supersedes any config-file tier assumption. */
        double takerBps,
        /** {@code (1 - takerBps/10_000)} precomputed at load time, 1e8-fixed — {@link
         * io.cfarb.strategy.Sizer} multiplies by this on every ladder walk and must never call
         * {@link io.cfarb.util.FixedPoint#fromDouble} on the hot path (that method's own javadoc). */
        long takerFeeMultiplierFixed,
        /** Exactly what {@code GET /api/v3/exchangeInfo} advertises this symbol supports for the
         * order {@code type} parameter — e.g. {@code {"LIMIT","MARKET","LIMIT_MAKER"}}. Startup
         * validation in live mode requires {@code cf-bot.exec.order-type} to be a member of this
         * set for every triangle's every leg. */
        Set<String> orderTypes) {
}

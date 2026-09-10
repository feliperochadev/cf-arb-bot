package io.cfarb.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cfarb.model.SymbolFilter;
import io.cfarb.util.FixedPoint;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Loads MEXC's real per-symbol lot-size/fee filters (startup only — never on the hot path, so
 * Jackson databind here is fine even though it's banned on market-data frames by rule R11). That
 * file is fetched from MEXC's PUBLIC {@code GET /api/v3/exchangeInfo} (no API key needed) and
 * mirrored byte-for-byte with {@code cf-arb-poc/config/mexc_filters.json} so the Java bot and the
 * Python research pipeline quantize identically (cf-arb-bot-plan.md §2.1, §11 verification item 3).
 *
 * <p>Bundled as a classpath resource ({@code src/main/resources/config/mexc_filters.json}) so the
 * service can start regardless of working directory — cf-arb-bot-review-plan.md Tier 2 step 2.2:
 * the previous CWD-relative {@code config/mexc_filters.json} path meant Terraform's deployed
 * service could never find this file (nothing provisioned it, and systemd's
 * {@code WorkingDirectory=/opt/cf-arb-bot} would not have contained it even if something had).
 * {@code cf-bot.filters-path} remains available as an explicit filesystem override for operators who
 * want to swap in a freshly re-fetched snapshot without rebuilding.
 */
public final class SymbolFilterLoader {

    private static final Logger LOG = Logger.getLogger(SymbolFilterLoader.class);

    private SymbolFilterLoader() {
    }

    public static Map<String, SymbolFilter> load(Path path) throws IOException {
        return parse(Files.readAllBytes(path), path.toString(), 0.0);
    }

    public static Map<String, SymbolFilter> load(InputStream in, String sourceDescription) throws IOException {
        return parse(in.readAllBytes(), sourceDescription, 0.0);
    }

    /** JOURNAL-TUNING-TASK.md T9: {@code takerDiscountPct} (0–100, from {@code cf-bot.fees.taker-discount-pct})
     * scales every symbol's {@code taker_bps}/{@code maker_bps} by {@code (1 - pct/100)} at load —
     * MEXC's MX-holding discount is not in {@code mexc_filters.json}. Validated by the caller
     * ({@code BotService}) before it gets here. */
    public static Map<String, SymbolFilter> load(Path path, double takerDiscountPct) throws IOException {
        return parse(Files.readAllBytes(path), path.toString(), takerDiscountPct);
    }

    public static Map<String, SymbolFilter> load(InputStream in, String sourceDescription, double takerDiscountPct)
            throws IOException {
        return parse(in.readAllBytes(), sourceDescription, takerDiscountPct);
    }

    private static Map<String, SymbolFilter> parse(byte[] bytes, String source, double takerDiscountPct)
            throws IOException {
        double feeScale = 1.0 - takerDiscountPct / 100.0;
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(bytes);
        JsonNode symbolsNode = root.get("symbols");
        if (symbolsNode == null) {
            throw new IOException("mexc_filters.json missing top-level 'symbols' object: " + source);
        }
        Map<String, SymbolFilter> out = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = symbolsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String symbol = e.getKey();
            JsonNode n = e.getValue();
            int qtyPrecision = requireInt(n, "base_asset_precision", symbol, source);
            int pricePrecision = requireInt(n, "quote_asset_precision", symbol, source);
            // cf-arb-bot-review-plan.md Tier 1 step 1.2 (discovered building it, not in the
            // original review): MEXC's live exchangeInfo shows XRPBTC at 9 decimal digits of price
            // precision, but this codebase's entire fixed-point system (FixedPoint.SCALE = 1e8) can
            // only represent 8. Submitting a price with FEWER decimals than the venue's advertised
            // maximum is always a valid subset of it (there is no tick-size/PRICE_FILTER on these
            // symbols, only PERCENT_PRICE_BY_SIDE, which is unrelated to decimal count) -- so
            // clamping here loses a small amount of price granularity on XRPBTC specifically, never
            // produces an invalid order. Widening FixedPoint.SCALE itself would be a much larger,
            // whole-codebase change and is out of this plan's scope.
            // Third-pass review finding (L5): the clamp above was entirely SILENT -- an operator
            // reading logs would have no way to know a symbol's real precision exceeded what this
            // system can represent. Loud now, and quantified: at a small-magnitude price (XRPBTC's
            // live price is order 1e-5), a single 1e-8 grid step is a MUCH larger fraction of the
            // price than the same absolute step is for a large-magnitude one (BTCUSDT, order 1e4) --
            // for XRPBTC specifically that fraction is already comparable to this bot's whole
            // min-net-bps threshold (cf-arb-bot-plan.md's own headline numbers), which is exactly the
            // kind of precision loss EdgeCalculatorTest's cross-check against the Python pipeline
            // cannot catch (both sides of that comparison share this same FixedPoint.SCALE ceiling).
            // This does not by itself prove a live discrepancy -- see CLAUDE.md's documented gap for
            // the credentialed probe that would -- but a WARN an operator can actually see is a
            // meaningfully better default than a comment nobody reads before going live.
            if (pricePrecision > 8) {
                LOG.warnf("mexc_filters.json symbol '%s': venue advertises quote_asset_precision=%d, "
                                + "clamped to FixedPoint.SCALE's 8-decimal ceiling -- at a small-magnitude "
                                + "price this can be a material fraction of cf-bot.strategy.min-net-bps; "
                                + "verify against a live captured frame before trusting this symbol's edge "
                                + "calc at full precision (see SymbolFilterLoader's javadoc)",
                        symbol, pricePrecision);
                pricePrecision = 8;
            }
            if (qtyPrecision > 8) {
                LOG.warnf("mexc_filters.json symbol '%s': venue advertises base_asset_precision=%d, "
                                + "clamped to FixedPoint.SCALE's 8-decimal ceiling", symbol, qtyPrecision);
                qtyPrecision = 8;
            }
            long qtyStep = FixedPoint.fromDouble(Math.pow(10.0, -qtyPrecision));
            long minQty = FixedPoint.fromDouble(requireDouble(n, "min_qty", symbol, source));
            long minNotional = FixedPoint.fromDouble(requireDouble(n, "min_notional", symbol, source));
            // JOURNAL-TUNING-TASK.md T9: apply the MX-token taker discount (feeScale = 1 - pct/100).
            // A no-op when takerDiscountPct is 0 (the default) or when taker_bps is already 0
            // (USDC/USD1 promotional pairs).
            double takerBps = requireDouble(n, "taker_bps", symbol, source) * feeScale;
            long feeMultiplierFixed = FixedPoint.fromDouble(1.0 - takerBps / 10_000.0);
            Set<String> orderTypes = new HashSet<>();
            JsonNode orderTypesNode = n.get("order_types");
            if (orderTypesNode != null) {
                for (JsonNode t : orderTypesNode) {
                    orderTypes.add(t.asText());
                }
            }
            // cf-arb-bot-review-plan.md (second pass) Tier A4: PERCENT_PRICE_BY_SIDE band, used to
            // clamp exec.Unwinder's cross-the-book reversal price. Absent (null in the snapshot) is
            // treated as "no band published" -- callers must not assume 0 means "no room to cross".
            double bidMultiplierUp = optionalDouble(n, "bid_multiplier_up", Double.NaN);
            double askMultiplierDown = optionalDouble(n, "ask_multiplier_down", Double.NaN);
            out.put(symbol, new SymbolFilter(
                    symbol,
                    n.get("base_asset").asText(),
                    n.get("quote_asset").asText(),
                    qtyStep,
                    qtyPrecision,
                    minQty,
                    minNotional,
                    pricePrecision,
                    takerBps,
                    feeMultiplierFixed,
                    orderTypes,
                    bidMultiplierUp,
                    askMultiplierDown));
        }
        return out;
    }

    private static int requireInt(JsonNode n, String field, String symbol, String source) throws IOException {
        JsonNode v = n.get(field);
        if (v == null) {
            throw new IOException("mexc_filters.json symbol '" + symbol + "' missing required field '"
                    + field + "': " + source);
        }
        return v.asInt();
    }

    private static double requireDouble(JsonNode n, String field, String symbol, String source) throws IOException {
        JsonNode v = n.get(field);
        if (v == null) {
            throw new IOException("mexc_filters.json symbol '" + symbol + "' missing required field '"
                    + field + "': " + source);
        }
        return v.asDouble();
    }

    /** Unlike {@link #requireDouble}, absence (or an explicit JSON {@code null}, which the loader's
     * own writer emits for a symbol with no published PERCENT_PRICE_BY_SIDE band) is not an error --
     * it returns {@code fallback} so callers can distinguish "no band published" from "band is 0". */
    private static double optionalDouble(JsonNode n, String field, double fallback) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? fallback : v.asDouble();
    }
}

package io.cfarb.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cfarb.exec.MexcOrderApi;
import io.cfarb.util.FixedPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

/**
 * PRE-LIVE-PLAN.md P1-4(b): reconciles real account balances against MEXC on boot, LIVE MODE ONLY,
 * before the feed connects or the detector subscribes — {@link Portfolio} is otherwise a single
 * in-memory number seeded from {@code cf-bot.capital.seed-usd} with no idea what a restart's real
 * account actually holds, and non-anchor inventory left over from an interrupted cycle is invisible
 * to it.
 *
 * <p><b>No USD conversion for most assets, deliberately.</b> This runs before the WebSocket feed
 * ever connects, so there is no live price for anything except the anchor's recognized ~1:1-USD
 * stablecoin peers ({@link #USD_STABLE_ASSETS}) — {@code cf-bot.capital.non-anchor-dust-usd} is
 * applied directly to those, in their own units. For every OTHER asset (BTC, ETH, XRP, ...) there
 * is no way to price a balance at boot without guessing, so ANY nonzero balance is treated as
 * stranded (fail closed, security rule S5) regardless of the configured dust threshold — "unknown
 * value" is never assumed to be dust. An unparseable balance field is treated the same way (a big
 * sentinel that always exceeds any allowance), never silently coerced to zero.
 */
public final class BalanceReconciler {

    private static final Logger LOG = Logger.getLogger(BalanceReconciler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Recognized as ~1 unit == ~$1 for the dust-usd threshold — every other non-anchor asset has
     * no live price available yet at boot time, so it gets no such allowance (see class javadoc). */
    private static final Set<String> USD_STABLE_ASSETS = Set.of("USDT", "USDC", "USD1", "BUSD", "DAI");

    /** Sentinel used for an unparseable balance field — large enough to always exceed any
     * configured dust allowance (forcing the asset into {@link Result#stranded}), far enough below
     * {@code Long.MAX_VALUE} that adding {@code free + locked} can never overflow. */
    private static final long UNPARSEABLE_SENTINEL_FIXED = Long.MAX_VALUE / 4;

    private final MexcOrderApi client;
    private final String anchorAsset;
    private final double nonAnchorDustUsd;

    public BalanceReconciler(MexcOrderApi client, String anchorAsset, double nonAnchorDustUsd) {
        this.client = client;
        this.anchorAsset = anchorAsset;
        this.nonAnchorDustUsd = nonAnchorDustUsd;
    }

    /** One non-anchor asset balance found above its dust allowance. */
    public record StrandedAsset(String asset, double balance) {
    }

    public record Result(long anchorBalanceFixed, List<StrandedAsset> stranded) {
        public boolean clean() {
            return stranded.isEmpty();
        }
    }

    /**
     * Blocking — called once from {@code BotService#onStart}, on the Quarkus startup thread,
     * before the feed connects or the detector subscribes (never a hot-path call, never invoked in
     * an automated test against a live venue per non-negotiable #8 — see
     * {@code BalanceReconcilerTest}'s stubbed {@link MexcOrderApi}).
     *
     * @throws IllegalStateException the account response has no entry at all for the configured
     *         anchor asset, or that entry's own free/locked balance is unparseable — either means
     *         something is badly wrong and there is no sane seed to fall back to.
     */
    public Result reconcile(long timeoutMs) throws ExecutionException, InterruptedException, TimeoutException,
            com.fasterxml.jackson.core.JsonProcessingException {
        String json = client.account(timeoutMs).get(timeoutMs, TimeUnit.MILLISECONDS);
        JsonNode root = MAPPER.readTree(json);
        JsonNode balances = root.get("balances");

        Long anchorFixed = null;
        List<StrandedAsset> stranded = new ArrayList<>();
        if (balances != null && balances.isArray()) {
            for (JsonNode b : balances) {
                String asset = b.path("asset").asText("");
                if (asset.isEmpty()) {
                    continue;
                }
                boolean isAnchor = asset.equals(anchorAsset);
                long freeFixed = parseAmount(b, "free", asset, isAnchor);
                long lockedFixed = parseAmount(b, "locked", asset, isAnchor);
                long totalFixed = freeFixed + lockedFixed;
                if (isAnchor) {
                    anchorFixed = totalFixed;
                    continue;
                }
                if (totalFixed <= 0) {
                    continue;
                }
                double total = FixedPoint.toDouble(totalFixed);
                double allowanceUsd = USD_STABLE_ASSETS.contains(asset) ? nonAnchorDustUsd : 0.0;
                if (total > allowanceUsd) {
                    stranded.add(new StrandedAsset(asset, total));
                }
            }
        }
        if (anchorFixed == null) {
            throw new IllegalStateException("BalanceReconciler: no " + anchorAsset
                    + " (cf-bot.capital.anchor-asset) entry in the account balances response");
        }
        return new Result(anchorFixed, List.copyOf(stranded));
    }

    /** {@code isAnchor} makes an unparseable field on the anchor asset itself fail the boot
     * immediately (via the sentinel colliding with nothing sane downstream would expect) rather
     * than silently seeding {@code Portfolio} from garbage — every OTHER asset instead gets flagged
     * as stranded, since a parse failure there just means "we don't actually know," never "zero". */
    private static long parseAmount(JsonNode balanceNode, String field, String asset, boolean isAnchor) {
        JsonNode v = balanceNode.get(field);
        if (v == null || v.isNull()) {
            return 0L;
        }
        String text = v.asText();
        if (text == null || text.isBlank()) {
            return 0L;
        }
        long parsed = FixedPoint.parse(text);
        if (parsed == Long.MIN_VALUE) {
            if (isAnchor) {
                throw new IllegalStateException("BalanceReconciler: unparseable " + field + " balance for "
                        + "anchor asset " + asset + ": \"" + text + "\"");
            }
            LOG.warnf("BalanceReconciler: unparseable %s balance for %s: \"%s\" -- treating as stranded "
                    + "(fail closed, S5)", field, asset, text);
            return UNPARSEABLE_SENTINEL_FIXED;
        }
        return parsed;
    }
}

package io.cfarb.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Map;

/**
 * All tunables under {@code cf-bot.*}, env-var overridable — same shape as
 * {@code cf-trader.config.TraderConfig} and {@code recorder-service.config.RecorderConfig}
 * (nested {@code Map<String, NestedInterface>} for venue/triangle/symbol config, so adding a
 * triangle or a symbol is a config change, never a code change — rule Q8).
 *
 * <p>{@link #dryRun()} defaults to {@code true} and this default must NEVER be flipped in code
 * (security rule S4). Live trading requires an explicit {@code CF_BOT_DRY_RUN=false} env override,
 * which {@code BotService} must log as a prominent startup warning — see cf-arb-bot-plan.md §5.5.
 */
@ConfigMapping(prefix = "cf-bot")
public interface BotConfig {

    /** Paper-trading default (S4). NEVER change this default in code — see CLAUDE.md. */
    @WithDefault("true")
    boolean dryRun();

    VenueConfig venue();

    /** Canonical MEXC symbols this bot subscribes to (cf-arb-bot-plan.md §5.6). */
    List<String> symbols();

    /** Optional filesystem override for the exchange-filter snapshot; absent means load the
     * bundled classpath resource (cf-arb-bot-review-plan.md Tier 2 step 2.2 — the previous
     * CWD-relative {@code config/mexc_filters.json} path meant the service could not start at all
     * unless launched from a specific working directory, and Terraform never provisioned the file). */
    java.util.Optional<String> filtersPath();

    /** Triangle definitions keyed by a human-readable name. */
    Map<String, TriangleConfig> triangles();

    StrategyConfig strategy();

    CapitalConfig capital();

    RiskConfig risk();

    ExecConfig exec();

    JournalConfig journal();

    interface VenueConfig {
        @WithDefault("wss://wbs-api.mexc.com/ws")
        String wsUrl();

        @WithDefault("https://api.mexc.com")
        String restUrl();

        /** Gate 0 step 4 measured aggre.depth@10ms as the clear winner over @100ms and the
         * (surprisingly slow, 500ms-cadence) limit.depth@20 snapshot channel — see
         * cf-arb-bot-plan.md §3 step 4 and §5.2. */
        @WithDefault("aggre.depth@10ms")
        String depthChannel();
    }

    interface TriangleConfig {
        @WithDefault("true")
        boolean enabled();

        /** "SYMBOL:BID" or "SYMBOL:ASK" per leg, exactly 3 entries, in traversal order. */
        List<String> legs();
    }

    interface StrategyConfig {
        /** 10 bps costs ~60-70% of PnL vs 5 bps; 20 bps yields ~2 trades/day (cf-arb-bot-plan.md §2). */
        @WithDefault("5.0")
        double minNetBps();

        @WithDefault("1.0")
        double slippageBufferBps();

        @WithDefault("250")
        long maxBookAgeMs();
    }

    interface CapitalConfig {
        @WithDefault("100.0")
        double seedUsd();

        @WithDefault("USDT")
        String anchorAsset();

        @WithDefault("true")
        boolean compound();
    }

    interface RiskConfig {
        /** The kill switch (security rule S7). */
        @WithDefault("50.0")
        double equityFloorUsd();

        /** Hard-clamped in code as well as config (security rule S6) — see risk/RiskGates. */
        @WithDefault("200.0")
        double maxNotionalUsd();

        @WithDefault("1")
        int maxOpenCycles();

        @WithDefault("30")
        int maxCyclesPerMinute();

        @WithDefault("250")
        long cycleCooldownMs();

        @WithDefault("3")
        int maxConsecutiveFailures();
    }

    interface ExecConfig {
        /** cf-arb-bot-review-plan.md Tier 1 step 1.3: {@code IOC} is not a real MEXC order-type
         * value (confirmed against the spot v3 API's published ENUM definitions 2026-09-07) --
         * there is no separate {@code timeInForce} parameter on {@code POST /api/v3/order}; the
         * {@code type} value itself carries that semantic, and the real value is
         * {@code IMMEDIATE_OR_CANCEL}. NOTE: none of this bot's 9 currently configured symbols
         * advertise support for it in {@code GET /api/v3/exchangeInfo} (only LIMIT/MARKET/
         * LIMIT_MAKER) -- startup validation in live mode (see BotService) fails closed on this
         * until either MEXC enables it for these symbols or the execution design changes. This is a
         * genuine open product question, not resolved by this config change alone. */
        @WithDefault("IMMEDIATE_OR_CANCEL")
        String orderType();

        @WithDefault("5000")
        long recvWindowMs();

        @WithDefault("1500")
        long legTimeoutMs();
    }

    interface JournalConfig {
        @WithDefault("./journal")
        String dir();

        /** Absent = local only. Never a secret — bucket name, not credentials. */
        java.util.Optional<String> s3Bucket();
    }
}

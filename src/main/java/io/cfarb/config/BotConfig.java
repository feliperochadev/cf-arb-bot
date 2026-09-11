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

    ObservabilityConfig observability();

    BookConfig book();

    FeesConfig fees();

    DetectorConfig detector();

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

        /** JOURNAL-TUNING-TASK.md T5: optional per-triangle notional cap in USD. Absent means the
         * triangle inherits the global {@code cf-bot.risk.max-notional-usd}. A present value is
         * subject to the SAME fail-the-boot validation as the global cap ({@code TriangleRegistry}
         * throws on a non-positive value, and on a value ABOVE the global cap — never a silent
         * widening, security rule S6). Lets a thin cross triangle (e.g. {@code usdt-eth-xrp-fwd})
         * be given $200 while a deep one keeps the full cap — JOURNAL-BPS-ANALYSIS.md §3.1 measured
         * depth-walk drag scaling ~linearly with size, 5–55 bps on the thin-cross triangles.
         *
         * <p>A bare {@code Optional*} (no {@code @WithDefault}) is SmallRye's idiom for "absent means
         * empty" — {@code TriangleRegistry} substitutes the global cap in that case. */
        java.util.OptionalDouble maxNotionalUsd();
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

        /** PRE-LIVE-PLAN.md P1-4(b): fetch real account balances on boot and seed {@code
         * state.Portfolio} from the anchor balance instead of {@link #seedUsd()} -- {@code
         * state.Portfolio} is otherwise a single in-memory number with no idea what a restart's
         * account actually holds. LIVE MODE ONLY; ignored (and never contacts the venue) in
         * dry-run, where {@link #seedUsd()} is always the seed. */
        @WithDefault("true")
        boolean reconcileOnBoot();

        /** A non-anchor asset balance above this (in that asset's OWN units for a recognized
         * ~1:1-USD stablecoin peer of the anchor; ANY nonzero balance for every other asset, since
         * no live price exists yet at boot to convert it — see {@code state.BalanceReconciler}'s
         * javadoc) FAILS THE BOOT (S5) as stranded inventory needing operator review, rather than
         * something the bot silently trades around. */
        @WithDefault("1.0")
        double nonAnchorDustUsd();
    }

    interface RiskConfig {
        /** The kill switch (security rule S7). */
        @WithDefault("50.0")
        double equityFloorUsd();

        /** The one per-cycle notional cap (security rule S6). {@code RiskGates} logs an ERROR and
         * REFUSES TO START if this is non-positive — a misconfigured cap is loud and fatal, never
         * silently defaulted or clamped. Otherwise trusted as configured; at runtime every cycle is
         * additionally bounded by live equity ({@code min(equity, cap)}) and the kill switch. (Until
         * 2026-09-10 a frozen {@code RiskGates.ABSOLUTE_MAX_NOTIONAL_USD = 1000.0} also clamped this
         * in code, which silently strangled every cycle to $1k once the seed moved to 4–5 figures.) */
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

        /** PRE-LIVE-PLAN.md P0-2(a): notional BUDGET across a rolling window, distinct from {@link
         * #maxNotionalUsd()} (bounds one order) and {@link #maxCyclesPerMinute()} (bounds a COUNT,
         * not a dollar sum). Neither existing gate binds a rapid sequence of full-sized fires — the
         * 2026-09-11 burst put 43 fires x $2,010 = $86,430 of notional through in 19s against $2,500
         * of equity. A non-positive value FAILS THE BOOT (S6), same as every other risk limit. */
        @WithDefault("5000")
        double maxNotionalPerWindowUsd();

        /** Width of the rolling window {@link #maxNotionalPerWindowUsd()} is measured over. A
         * non-positive value FAILS THE BOOT (S6). */
        @WithDefault("60000")
        long notionalWindowMs();

        /** PRE-LIVE-PLAN.md P1-4(a): {@code risk.KillSwitch}'s second, looser consecutive-failure
         * counter for a broken cycle that moved NO real inventory (loss == 0, e.g. a leg-0
         * zero-fill) — a free missed trade, not a failure. A non-positive value FAILS THE BOOT (S6),
         * same as every other risk limit. */
        @WithDefault("25")
        int maxConsecutiveNoFill();
    }

    interface ExecConfig {
        /** cf-arb-bot-review-plan.md's SECOND independent review (REVIEW.md) caught an error the
         * FIRST remediation pass introduced: that pass changed this default to
         * {@code IMMEDIATE_OR_CANCEL} on the theory that {@code IOC} "is not a real MEXC order-type
         * value." That theory was wrong. Confirmed twice against MEXC's own published spot v3 API
         * reference (section "New Order"): the documented {@code type} ENUM is
         * {@code LIMIT}/{@code MARKET}/{@code LIMIT_MAKER}/{@code IOC}/{@code FOK}, and
         * {@code POST /api/v3/order}'s request parameter table has NO {@code timeInForce} field at
         * all (it appears only in responses/other endpoints) -- so neither the original code's
         * {@code IOC} nor the first pass's {@code IMMEDIATE_OR_CANCEL} was resolved by adding a
         * {@code timeInForce} parameter; {@code IOC} was the correct short-form value the whole
         * time. Restored here.
         *
         * <p>Separately confirmed live across ALL 2074 MEXC spot symbols (not just this bot's 9-13):
         * {@code exchangeInfo}'s per-symbol {@code order_types} is always exactly one of
         * {@code [LIMIT,MARKET,LIMIT_MAKER]} or {@code [LIMIT,LIMIT_MAKER]} -- {@code IOC}/
         * {@code FOK} never appear there for any symbol on this venue, for any order type this bot
         * could configure. A strict membership check is therefore structurally unsatisfiable
         * whenever this is IOC/FOK; {@code BotService.validateOrderType} treats that specific
         * combination as unverified-but-permitted (a loud startup WARN), not a boot failure --
         * settling it for real needs the credentialed 1-USDT live probe this project's release gate
         * already requires before {@code dry-run=false}. */
        @WithDefault("IOC")
        String orderType();

        @WithDefault("5000")
        long recvWindowMs();

        @WithDefault("1500")
        long legTimeoutMs();

        /** cf-arb-bot-review-plan.md (second pass) Tier A4: basis-point buffer {@code exec.Unwinder}
         * crosses the CURRENT top of book by when reversing a held asset back toward the anchor --
         * pricing a reversal at the original leg's entry-boundary price (the previous design) fails
         * deterministically in both directions, since it requires the market to move favorably
         * rather than crossing it. Clamped per-symbol inside {@code SymbolFilter}'s
         * {@code PERCENT_PRICE_BY_SIDE} band before submission; 40 bps sits comfortably inside even
         * the tightest configured band (BTCUSDT/ETHUSDT at 0.5%) and well outside any observed
         * spread on these symbols. */
        @WithDefault("40")
        long unwindCrossBps();

        /** cf-arb-bot-review-plan.md (second pass) Tier A7 / REVIEW.md MED-10: an {@link
         * io.cfarb.model.OrderIntent} older than this when the executor thread finally dequeues it
         * is dropped rather than executed -- triangular arbitrage opportunities live 50-150ms;
         * acting on one that is already older than that all but guarantees leg failures. */
        @WithDefault("150")
        long maxIntentAgeMs();
    }

    interface JournalConfig {
        @WithDefault("./journal")
        String dir();

        /** Absent = local only. Never a secret — bucket name, not credentials. */
        java.util.Optional<String> s3Bucket();

        /** Third-pass review finding: minimum interval between two journaled REJECT events for the
         * SAME triangle. Every candidate clearing the cheap risk gates hits a reject path, and the
         * per-triangle cooldown only advances on an actual fire, so the previous unsampled form
         * wrote ~900 NDJSON lines/second (~10 GB/day) at the measured feed rate — enough to fill the
         * deployed 20 GB root volume in about two days of DRY-RUN. Fires and order-queue-full events
         * are never sampled; suppressed rejects are counted as {@code cfarb.journal.suppressed}.
         * Set to 0 to journal every reject (the old behavior — only sane for short local captures). */
        @WithDefault("1000")
        long rejectSampleMs();
    }

    /**
     * JOURNAL-TUNING-TASK.md T1c: L2 book self-heal. A crossed book (top bid ≥ top ask) has no
     * self-healing path in {@code L2Book.apply()} today — once it crosses it stays crossed until a
     * version-chain gap forces a {@code reset()}, which JOURNAL-BPS-ANALYSIS.md §5.5 shows can be
     * hours. That is a silent, permanent, per-symbol outage (17 of 17 sessions: the BTCUSDT
     * triangles die once and never recover).
     */
    interface BookConfig {
        /** Grace period a book may stay crossed before {@code L2Book.apply()} force-{@code reset()}s
         * it and lets it re-warm. {@code reset()} clears {@code trusted}, so the book fails closed
         * (rule S5) while rebuilding — no risk of trading off a half-rebuilt ladder. Checked on the
         * Netty event-loop thread inside {@code apply()} (the book's owning thread — a cross-thread
         * {@code reset()} from the watchdog timer would race the level arrays, so detection and the
         * reset both stay on that one thread; the watchdog only observes and journals the event).
         * A non-positive value DISABLES self-heal entirely and logs a prominent startup WARN — the
         * pre-JOURNAL-TUNING behavior, only sane for a short diagnostic capture. */
        @WithDefault("500")
        long maxCrossedMs();

        /** PRE-LIVE-PLAN.md P0-2(c): a book that just {@code reset()} (crossed-latch self-heal, a
         * version-chain gap, or reconnect) is {@code isTrusted()} again within roughly 55ms on a
         * ~900msg/s symbol (see {@code L2Book#lastResetNanos}'s javadoc) — a ladder rebuilt from a
         * handful of deltas, not a real book. {@code OpportunityDetector} refuses any triangle with
         * a leg reset more recently than this, regardless of {@code isTrusted()}. {@code 0} DISABLES
         * the quarantine entirely and logs a startup WARN — only sane for a short diagnostic
         * capture; a negative value FAILS THE BOOT (S6). */
        @WithDefault("2000")
        long postResetQuarantineMs();
    }

    /**
     * JOURNAL-TUNING-TASK.md T9: fee-model adjustment. {@code mexc_filters.json} carries the
     * undiscounted per-symbol taker/maker commission straight from {@code exchangeInfo}. MEXC's
     * MX-token holding discount (MEXC-PAIR-EXPANSION.md §2: holding ≥ 500 MX for 24 h → 50 % taker
     * discount, 5.0 → 2.5 bps on the standard USDT legs) is not reflected there. This knob scales
     * every symbol's taker/maker bps at load time so the operator does not have to hand-edit the
     * snapshot.
     */
    interface FeesConfig {
        /** Percentage taker/maker fee reduction applied to EVERY symbol's {@code taker_bps} /
         * {@code maker_bps} at load ({@code effective = raw * (1 - pct/100)}). {@code 0.0} = no
         * change (the shipped default — {@code mexc_filters.json}'s numbers used verbatim). Set to
         * {@code 50.0} once the operator holds ≥ 500 MX and has verified the tier is actually 50 %
         * (JOURNAL-TUNING-TASK.md T9 flags it as also discussed as 20 %). A value outside
         * {@code [0, 100)} fails the boot. Zero-fee symbols (USDC/USD1 pairs) are unaffected —
         * {@code 0 * anything = 0}. */
        @WithDefault("0.0")
        double takerDiscountPct();
    }

    /**
     * Opt-in console visibility for a local dry-run, all of it OFF the hot tick path. The NDJSON
     * journal ({@code cf-bot.journal.dir}) and Prometheus ({@code /q/metrics}) remain the real
     * telemetry — this exists purely so an operator watching a local run can see the pipeline
     * working without curling {@code /api/v1/state} in a loop. Every key defaults OFF; the
     * {@code %dev} profile turns {@link #consoleReport()} and {@link #echoEvents()} on so
     * {@code quarkus:dev} is verbose out of the box.
     *
     * <p>Neither knob adds a thread or a hand-off (non-negotiable #5): the rolling report runs on
     * the existing Vert.x timer thread (already "read-only diagnostic reads" in CLAUDE.md's
     * threading table, same mechanism as the latency-snapshot timer) and the event echo runs on the
     * existing {@code cf-arb-journal-writer} thread. The Netty tick path is untouched.
     */
    interface ObservabilityConfig {
        /** Emit a rolling activity summary (frames/s, evaluations, near-misses, fires, equity, book
         * warmth, latency percentiles) to the console every {@link #consoleReportIntervalMs()}. */
        @WithDefault("false")
        boolean consoleReport();

        @WithDefault("5000")
        long consoleReportIntervalMs();

        /** Echo each NDJSON journal event (opportunity/fire, cycle, broken_cycle, sampled reject,
         * feed_reconnect, latency_snapshot) to the console from the journal-writer thread as it is
         * appended. Chatty, but never on the tick path. */
        @WithDefault("false")
        boolean echoEvents();
    }

    /**
     * PRE-LIVE-PLAN.md P0-2(b): tuning for {@code OpportunityDetector}'s per-triangle duplicate-fire
     * suppression (DUPLICATE-FIRE-TASK.md "Fix A"). Fix A suppresses only when all three legs' fire
     * signatures are unchanged, but a resetting-often leg (BTCUSDT resets ~79/h) keeps ONE leg's
     * write stamp advancing continuously — "one churning leg unlocks re-fires against two frozen
     * ones" — so the all-three rule almost never actually fires
     * (cfarb_detector_duplicate_fire_total: 10 suppressions across 11h, on one triangle).
     */
    interface DetectorConfig {
        /** A leg counts toward duplicate suppression only when its base quantity is at least this
         * fraction of its touch quantity — stops a deep, effectively-constant leg (USDCUSDT's top)
         * from vetoing suppression on its own. Outside {@code (0, 1]} FAILS THE BOOT (S6). */
        @WithDefault("0.10")
        double duplicateMaterialFraction();

        /** A candidate identical to the last fire on every MATERIAL leg is suppressed only while the
         * last fire is within this many ms — an old fire's signature does not veto a fresh one
         * forever. */
        @WithDefault("30000")
        long duplicateWindowMs();

        /** PRE-LIVE-PLAN.md P0-2(d): stale-leg guard. If one leg's top ({@code L2Book
         * #lastTopChangeNanos}) has not changed in at least this many ms while another leg's top
         * changed within {@link #staleLegActiveMs()}, the edge is refused as lag, not a real
         * opportunity — the 12:30:16 signature (BTCUSDC's bid sitting 161.58 above BTCUSDT's ask
         * while every other sample that window ran -0.69 to -6.61 bps). Either this or {@link
         * #staleLegActiveMs()} {@code <= 0} DISABLES the guard entirely (startup WARN, not a boot
         * failure — this is a tuning knob, not a safety limit). */
        @WithDefault("1000")
        long staleLegFrozenMs();

        /** See {@link #staleLegFrozenMs()}. */
        @WithDefault("200")
        long staleLegActiveMs();
    }
}

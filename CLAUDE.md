# cf-arb-bot — CLAUDE.md

Live, MEXC-only, triangular-arbitrage PoC. Full spec: `../cf-arb-bot-plan.md`. Read that first — this
file is the condensed set of non-negotiables for anyone (human or agent) touching this code.

## This module supersedes two things, deliberately, and ONLY for itself

1. **`cf-trader-build-plan.md` §8** ("Delete `executeLive`... under Path C there is no live path") —
   superseded FOR THIS MODULE ONLY. `cf-trader` and `recorder-service` remain Path C: paper-only,
   no live execution, ever. This module exists specifically to place real orders, under the gates
   below.
2. **`recorder-service/CLAUDE.md` #1** ("never parse on the receive path") — a recorder has no
   reason to parse; a bot must. The rule THIS module follows instead is `cf-trader`'s: **zero
   allocation, zero locks, zero blocking I/O on the tick path.** Parsing is allowed; allocating is
   not (outside the documented rare-branch exceptions below).

**Untouched:** `recorder-service`'s "no live-venue write capability of any kind" stays true of
*that* service. This is a separate process, separate JVM, separate deploy. Nothing authenticated
ever enters `recorder-service`.

## Non-negotiables

1. **`cf-bot.dry-run=true` is the default and must NEVER be changed in code.** Live requires an
   explicit `CF_BOT_DRY_RUN=false` env override AND a prominent startup warning (see
   `BotService.logStartupSafetyBanner`) — security rule S4.
2. **Every risk gate fails closed** (S5): missing config, stale/crossed/untrusted book, unknown
   rate-limit state, equity below the floor → no order. See `risk.RiskGates` / `risk.KillSwitch`.
3. **A misconfigured risk limit FAILS THE BOOT — never a silent default** (S5/S6).
   `RiskGates.failStartup()` logs an ERROR and aborts if `max-notional-usd`, `max-open-cycles`, or
   `max-cycles-per-minute` is ≤ 0 — not quietly widened via `Math.max(1, …)` to a value the operator
   never chose. `cf-bot.risk.max-notional-usd` is the one per-cycle notional cap and is otherwise
   trusted as configured; at runtime every cycle is additionally bounded by live equity
   (`min(equity, cap)`) and the kill switch. (Until 2026-09-10 a frozen
   `RiskGates.ABSOLUTE_MAX_NOTIONAL_USD = 1000.0` also clamped the cap in code, which silently
   strangled every cycle to $1k once the seed moved to 4–5 figures; a later `absolute-max-notional-usd`
   config knob was tried and then dropped as redundant.)
4. **No secrets anywhere in code, config, tests, logs, or Terraform state** (S1/S2/S3).
   `MEXC_API_KEY`/`MEXC_API_SECRET` arrive ONLY via environment (SSM SecureString in Tokyo — see
   `../cf-arb-bot-plan.md` §6.2). The key must be TRADE-ONLY, no withdrawal permission, IP-allowlisted
   to the deployed Elastic IP.
5. **Declared thread hand-offs only, and they must be declared, not smuggled in** — this module
   adds TWO beyond `cf-trader`'s single Strategist→Orderer hand-off: detector→executor (the JCTools
   SPSC queue carrying `OrderIntent`) and the journal writer. Both exist because three sequential
   REST round trips cannot run on an event-loop thread (R5/Q4). Adding a THIRD requires updating
   `../cf-arb-bot-plan.md` §5.1 first.
6. **MEXC protobuf decoding is allocation-free and iterative, never recursive** (R1/R8/S11).
   `feed.ProtobufWalker` and `feed.MexcDepthDecoder` do not allocate on the steady-state path and
   never deserialize into POJOs via a generic library (R11) — no `protobuf-java`, ever.
7. **`EdgeCalculator` is the one piece that must be exactly right.** It quantizes every leg to
   MEXC's real lot-size/min-notional filters and VWAP-walks the real ladder — this is what makes
   the bot correctly refuse `SOLBTC`-family triangles at a $100 seed instead of firing on a
   fictional edge (`../cf-arb-bot-plan.md` §2.1, §5.3). Any change here needs a cross-check against
   `cf-arb-poc/cfarb/stage2_cycles.evaluate_cycle` (see `EdgeCalculatorTest`).
8. **Never hit a live exchange in CI** (Q10). Fixtures are real captured frames
   (`src/test/resources/fixtures/`, extracted from `recorder-service/data/mexc/`) or hand-built
   synthetic books — never a live network call in an automated test.
9. **A new MEXC channel must be probed live before being trusted** — MEXC's own docs list channels
   (`increase.*`, bare `bookTicker`) that are silently blocked in practice. See
   `feed.MexcProtocol`'s javadoc for the story. `exec.UserDataStream`'s listenKey flow is marked
   unverified for exactly this reason — do not treat it as load-bearing until Phase 1 confirms it
   live.
10. **`Sizer` is deliberately stricter than the offline Python backtest**: a leg that the displayed
    ladder cannot fill IN FULL is rejected, not partial-filled — see its javadoc for why (leg-failure
    risk reduction). Do not "fix" this to match the backtest's leniency without re-reading that
    reasoning.

## Known, documented gaps (not oversights — see the referenced code for why)

- **CORRECTED 2026-09-07 (REVIEW.md's second independent review):** the entry above previously read
  "`IMMEDIATE_OR_CANCEL` is not currently supported by MEXC... this is the actual remaining blocker
  on live mode." That was WRONG on two counts, confirmed by fetching MEXC's own published spot v3
  API reference directly (twice): (1) `IMMEDIATE_OR_CANCEL`/`FILL_OR_KILL` are not even the venue's
  real short-form values — the documented `type` ENUM is `LIMIT`/`MARKET`/`LIMIT_MAKER`/`IOC`/`FOK`,
  and there is NO separate `timeInForce` request parameter on `POST /api/v3/order` at all (it does
  not appear in the request parameter table); (2) confirmed live across ALL 2074 MEXC spot symbols,
  not just this bot's 9-13: `exchangeInfo`'s per-symbol `order_types` is always exactly one of
  `[LIMIT,MARKET,LIMIT_MAKER]` or `[LIMIT,LIMIT_MAKER]` — `IOC`/`FOK` never appear there for ANY
  symbol on this venue. `cf-bot.exec.order-type` is restored to `IOC`. Because `exchangeInfo` can
  never confirm it by membership, `BotService.validateOrderType` treats this specific combination
  as **unverified-but-permitted** (a loud startup WARN in live mode), not a boot failure — the
  credentialed 1-USDT live probe (cf-arb-bot-plan.md's own release gate) is what actually settles
  this, not a doc reading. `ETHUSDC`/`SOLUSDC`/`XRPUSDC` additionally advertise no `MARKET` at all —
  relevant to `exec.Unwinder`'s MARKET fallback, which checks per-symbol support rather than
  assuming universal availability.
- `exec.UserDataStream` is unverified against the live listenKey endpoint (Phase 1 TODO).
- **No real balance reconciliation on boot** — `state.Portfolio` is a single in-memory number seeded
  from `cf-bot.capital.seed-usd`; a restart does not fetch or reconcile the live account, and
  non-anchor inventory left over from an interrupted cycle is invisible to it. Required before any
  unattended live run (`cf-arb-bot-review-plan.md` Tier 3).
- **RESOLVED 2026-09-07 (REVIEW.md MAJ-02, second pass):** this entry previously said unwind
  reversal pricing used each leg's own stale detection-time price, deferred to Tier 3. Fixed:
  `exec.Unwinder` now prices reversals off `book.L2Book`'s published top-of-book (a declared
  cross-thread read, not a new hand-off — see `L2Book`'s javadoc and the threading table below),
  crossing it by `cf-bot.exec.unwind-cross-bps` and clamping inside the symbol's own
  `PERCENT_PRICE_BY_SIDE` band. A symbol with no usable top (untrusted/empty, e.g. mid-reconnect)
  falls back to `MARKET` where the venue advertises it; where it does not
  (`ETHUSDC`/`SOLUSDC`/`XRPUSDC`), the position is left stranded and the kill switch trips
  immediately (`KillSwitch.recordUnrecoverableInventory`) rather than submitting a knowingly-doomed
  limit order.
- **AMENDED 2026-09-09 (third-pass review):** that `MARKET` fallback sized BOTH directions with a
  base `quantity`, derived by dividing the held amount by a placeholder reference price of `1.0`.
  Harmless for a reversal that SELLs (the held amount is already base-denominated); a currency error
  for one that BUYs, where the held amount is in the QUOTE asset — on `usdt-btc-usdc-fwd` that turned
  ~100 USDC in hand into `MARKET BUY 100 BTC`. A `MARKET` BUY is now sized with `quoteOrderQty` and
  carries no `quantity` at all. **`quoteOrderQty` is unverified against MEXC** in the same sense as
  `order-type=IOC` (documented in the Binance-family spot v3 contract, never put through a
  credentialed request here); it fails safe — an unsupported parameter 4xx-rejects into
  `REJECTED_PRESUBMIT`, i.e. "stranded, operator review", never a wrongly-sized order — and the
  1-USDT live probe must exercise it before `dry-run=false`.
- **FIXED 2026-09-09 (third-pass review):** a PARTIAL fill used to strand the remainder of the
  PREVIOUS leg's proceeds. `Unwinder`'s model ("at most ONE leg's output is ever in hand and not yet
  converted forward") is false for exactly the PARTIAL case `CycleExecutor` routes into it: a leg
  that filled 40% consumed only 40% of what it was handed, and the rest sat un-reversed, un-flagged,
  and booked by `handleBrokenCycle` as a 100% loss while the asset was still in the account.
  `CycleState.Leg.inputAmountFixed` now records what each leg was handed, and the unwind walk carries
  `input - executed` backwards through the reversal chain (excluding leg 0, whose from-asset is the
  anchor and whose actual spend `anchorSpent` already measures).
- **FIXED 2026-09-09 (third-pass review, Medium/Low sweep):**
  - `exec.CycleExecutor`'s `UNKNOWN` leg status now trips the kill switch IMMEDIATELY
    (`KillSwitch.recordUnrecoverableInventory`), matching `CycleState.LegStatus#UNKNOWN`'s own
    contract — it previously only called `recordFailure` (3-strikes), so Portfolio could go
    un-debited for up to 2 more cycles while real non-anchor inventory sat unaccounted for.
  - `util.FixedPoint.mulDiv`'s overflow branch no longer allocates `BigInteger`s — a single
    BTCUSDT-sized top-of-book level already overflows the plain-`long` fast path, so this was hit
    on essentially every major-pair ladder walk on the Netty event-loop thread (a bigger R1 breach
    than the `full.getBytes()` copy below). Replaced with an allocation-free unsigned 128-by-64 bit
    division, cross-checked against `BigInteger` over 100k random triples in `FixedPointTest`.
  - `strategy.Sizer.fillAsk` now caps the quantized quantity so a single IOC limit order's notional
    AT its own worst-touched price never exceeds the modeled budget — a multi-level walk could
    previously accumulate `baseFilled` from cheaper early levels such that `baseFilled * worstPrice`
    exceeded `candidateNotional`, risking an insufficient-balance rejection MEXC's own balance check
    would raise at the boundary price, not the VWAP this bot modeled.
  - `BotService`'s feed watchdog escalation counter was UNREACHABLE: `forceReconnect()`'s own
    `books.resetAll()` zeroed every book's `updateCount`, so the very next tick read `anyWarmed=false`
    and silently reset the counter to 0 regardless of whether the reconnect restored data — a feed
    that stayed dark after reconnecting could never reach `FEED_DEAD_TRIP_THRESHOLD`, so the kill
    switch could never trip on a genuinely dead feed. The decision logic is now a pure, unit-tested
    static method (`BotService.decideWatchdogAction`, see `BotServiceWatchdogTest`) that only clears
    the escalation on CONFIRMED fresh data.
  - `exec.OrderReconciler`: a 4xx placement rejection is no longer automatically
    `REJECTED_PRESUBMIT` — venue code `-1007` ("send status unknown") and any unparseable rejection
    body now fail closed into reconciliation instead. `isNonTerminal` now fails CLOSED (an
    unrecognized or missing `status` field triggers the same cancel-and-verify path a known-resting
    order gets, not "trust it as final"). A cancel-triggered re-query that STILL reports non-terminal
    is now `UNKNOWN`, not fed into `classifyFill` as if it were authoritative.
  - `risk.RiskGates`'s clock-skew gate no longer latches `clockSkewKnown=true` forever on the first
    sample — a sample older than 180s (3x the 60s sampling cadence) degrades back to "unknown" and
    fails closed in live mode. Tolerance halved from the full `recvWindow` (MEXC's own `-1021`
    boundary, zero margin) to half of it.
  - `exec.Unwinder`'s reversal pricing now checks `L2Book.isTrusted()`/book age (5s) before trusting
    a published top-of-book — a book that goes quietly stale WITHOUT a `reset()` (a half-open socket
    the watchdog hasn't caught yet, a thin symbol that hasn't ticked) still publishes a real,
    non-sentinel top, just an increasingly out-of-date one; crossing an old top by the fixed
    `unwind-cross-bps` buffer is the MAJ-02 failure one level removed. `clampToPriceBand`'s FLOOR
    (never its cap) is now rounded up to a representable tick before use, so the later on-wire
    truncation (`FixedPoint.toPlainString` never rounds) can't push the submitted price below the
    venue's real `PERCENT_PRICE_BY_SIDE` floor. **Not resolved:** whether MEXC's `PERCENT_PRICE_BY_SIDE`
    reference price is truly the current top-of-book or a trailing average is still unverified — see
    the live-probe gaps below.
  - `SymbolFilterLoader`'s price/quantity decimal-precision clamp (XRPBTC's real
    `quote_asset_precision` is 9, clamped to `FixedPoint.SCALE`'s 8-decimal ceiling — see the class's
    own javadoc) now logs a loud, symbol-named `WARN` at startup instead of clamping silently. At
    XRPBTC's small price magnitude a single 1e-8 grid step is a much larger fraction of the price
    than the same absolute step is for a large-magnitude symbol (BTCUSDT) — comparable, in fact, to
    this bot's whole `min-net-bps` threshold — which is exactly the kind of precision loss
    `EdgeCalculatorTest`'s cross-check against the Python pipeline cannot catch (both sides share the
    same `FixedPoint.SCALE` ceiling). This does not by itself prove a live discrepancy; the
    credentialed probe that would is still the open gap below.
- **ADDED 2026-09-10 (JOURNAL-TUNING-TASK.md P0 — `JOURNAL-BPS-ANALYSIS.md` acted on):**
  - **`L2Book` crossed-latch self-heal (T1c).** A crossed book (`bidPx[0] >= askPx[0]`) had no
    in-place uncross — it stayed unusable until a version-chain gap forced a `reset()`, which the
    24 h capture showed can be hours (§5.5: 17/17 sessions the BTCUSDT triangles die once and never
    recover). `L2Book.apply()` now tracks `crossedSinceNanos` and, once a book has been crossed
    longer than `cf-bot.book.max-crossed-ms` (default 500; `<= 0` disables + WARNs), force-`reset()`s
    it **on the Netty thread inside `apply()`** — NOT from the watchdog timer, which cannot touch the
    level arrays (single-writer invariant / non-negotiable #5). The feed watchdog only observes:
    one rate-limited WARN per crossed episode (`cfarb.book.crossed{symbol}`) and a `book_reset`
    journal event + `cfarb.book.reset{symbol,reason}` each time `crossedResetCount()` advances.
    `GET /api/v1/books` (T1a) exposes per-symbol `trusted`/`crossed`/`empty`/`ageMs`/`spreadBps`/… —
    a negative `spreadBps` is the latch directly visible. **The real fix (T1e: wider ladder / prune
    by price distance / periodic re-sync) is deferred** — it needs the T1a/T1d evidence and a clean
    re-capture; the self-heal is the mitigation that works regardless of root cause. `L2BookTest`
    reproduces the latch with synthetic frames and asserts the self-heal breaks it.
  - **Reject journaling: best-per-window, not first-per-window (T2).** `OpportunityDetector` now
    keeps the highest-`net_bps` (for `unfillable`, highest-`gross_bps`) candidate per
    `reject-sample-ms` window in primitive arrays and emits THAT at window close, with
    `sampled_from` on the line. Same line count / file size / R1 budget; the first candidate of a
    window is the provisional keep (not counted suppressed), every later one folded in is
    (`cfarb.journal.suppressed` unchanged in meaning). Emission stays edge-triggered by the next
    candidate after expiry — a quiet triangle holds its last window unemitted (acceptable; the
    reader has `sampled_from`).
  - **`opportunity` events carry per-leg depth (T4):** `leg_top_px` / `leg_touch_qty` /
    `leg_worst_px` / `leg_base_qty`, flat 3-arrays, on the sampled + fired paths only (never for a
    suppressed candidate). `EdgeCalculator.Result` gained `legTopPriceFixed[]`/`legTouchQtyFixed[]`
    and now zeroes every per-leg array at the top of `evaluate()` so an `unfillable` line (whose
    second pass returns mid-loop) never carries a previous triangle's stale leg data.
  - **Stale-skip counters (T3):** `cfarb.detector.stale_skip{triangle}` +
    `cfarb.detector.stale_skip_leg{symbol}` — the most common `evaluate()` outcome, previously
    silent. Pre-resolved into flat arrays by `BotMetrics.initRuntimeCounters()` (hot path stays a
    lock-free array increment).
  - **Per-triangle notional cap (T5):** `cf-bot.triangles.<name>.max-notional-usd`, resolved in
    `TriangleRegistry` (parallel `long[]`, `OpportunityDetector` sizes at
    `min(eligibleBalance, triangleCap)`). Absent → inherits the global cap; a non-positive value,
    or one ABOVE the global cap, FAILS THE BOOT (never a silent widening — S6). The global
    `cf-bot.risk.max-notional-usd` default was cut **$20,000 → $1,000** (analysis §3: drag scales
    ~linearly with size; the best 24 h episode went −19.6 bps @ $10k → +0.7 bps @ $100).
  - **MX-token fee discount (T9):** `cf-bot.fees.taker-discount-pct`, set to **`50.0`**.
    `SymbolFilterLoader` scales every symbol's `taker_bps` by `(1 - pct/100)` at load, so a 5.0 bps
    standard USDT leg → 2.5 bps, `GOLD(XAUT)USD1` 1.0 → 0.5, and an already-0.0 bps leg (every
    USDC/USD1 pair, `XRPUSDT`, `USDCUSDT`) stays 0. **Tier verified 2026-09-10 against MEXC's own
    fee pages:** standard spot taker 0.05 %; holding ≥ 500 MX for 24 h → **50 %** discount →
    0.025 %. (The separate, non-stackable "MX Deduction" feature is 20 %; MEXC auto-applies the
    greater, so a ≥ 500 MX holder gets 50 %.) **Assumes the trading account holds ≥ 500 MX** — drop
    to `20.0` if only MX Deduction is enabled, `0.0` for undiscounted. Value outside `[0, 100)`
    fails the boot. Flows through `SymbolFilter` into both `EdgeCalculator` (`gross_bps`) and
    `Sizer` (`net_bps`).
  - **Still deferred from the task:** T1e (real book fix), T6 (per-symbol `max-book-age-ms`),
    T7 (`config_snapshot` event), T10 (triangle-universe pruning), T11 (`min-net-bps` /
    `slippage-buffer-bps` — operator, and only after a clean re-capture). **T8 landed 2026-09-10** —
    see `DYNAMIC-SIZING-TASK.md` below.
- **ADDED 2026-09-10 (`DUPLICATE-FIRE-TASK.md` — "Fix A", `JOURNAL-BPS-ANALYSIS.md` §12–§15):**
  duplicate-fire suppression. The detector fired the same `usdt-btc-usdc-fwd` opportunity 5× in
  1.3 s (identical `detected_net_bps` to 10 dp, spaced at `cycle-cooldown-ms`) because
  `RiskGates.claim` only advances a *time* cooldown and, in dry-run, the paper fill never consumes
  depth — so `EdgeCalculator` kept recomputing the identical edge. Each fire now gets a per-triangle
  **signature** `(worstPrice, baseQty, writeSeq)` per leg; a triangle will not re-fire while that
  signature is unchanged. `L2Book` carries monotonic per-level write stamps (`bidWriteSeqAt` /
  `askWriteSeqAt`), **never reset** — even by `reset()` — so a re-warmed book (T1c self-heal) always
  produces a strictly higher stamp and re-fires naturally, no reset handling. `Sizer.Result`
  carries `maxWriteSeq` across every consumed level; `EdgeCalculator.Result` surfaces it as
  `legWriteSeq[3]`. The check sits in `OpportunityDetector.evaluate` immediately before
  `riskGates.claim` (a suppressed duplicate burns no cooldown/rate-limit budget); the signature is
  stored **only after a successful `orderQueue.offer`** (a rolled-back claim stays re-fireable).
  Unconditional (live *and* dry-run), no new thread hand-off, no allocation, no config knob. Rides
  the sampled reject path as `reject_reason: "duplicate-signature"` + counter
  `cfarb.detector.duplicate_fire{triangle}`. **Documented blind spot (false negative, safe side):**
  `aggre.depth@10ms` emits *net* changes over a 10 ms window, so a take-and-replace at exactly the
  same qty inside one window bumps no stamp — a genuinely new opportunity is suppressed (one missed
  +$0.155). No symmetric false-positive path, and the ~13:1 broken-vs-won payoff asymmetry means the
  failure lands on the safe side by construction. **Out of scope:** "Fix B" (a consumption ledger in
  `Sizer` that makes dry-run *fill* behaviour realistic) — needs a plan §5.3 update. This makes the
  fire *count* honest, not the paper-fill *P&L*.
- **ADDED 2026-09-10 (`DYNAMIC-SIZING-TASK.md`, Phase 1 + Phase 2):** gross early-out and dynamic
  per-tick sizing.
  - **Phase 1 — gross early-out.** `net_bps ≤ gross_bps` always (the ladder walk only moves to
    worse prices, quantization only floors — confirmed empirically, 0 of 41,839 paired samples had
    `net > gross`), so `grossBps ≤ gate` proves no size can clear the fire gate before the per-leg
    ladder walk ever runs. Measured over 7.92M evaluations: 99.54% could not possibly have cleared
    it. `EdgeCalculator.evaluate` gained a 5-arg overload (`bailBelowGrossBps`); the existing 4-arg
    entry point `EdgeCalculatorTest`'s Python cross-check calls delegates to it with
    `Double.NEGATIVE_INFINITY` and is byte-for-byte unchanged (non-negotiable #7). A bailed
    candidate is journaled as `reject_reason: "below-gross-ceiling"` — distinct from `"unfillable"`
    (the walk ran and failed) — and does **not** increment `cfarb.opportunities.rejected_unfillable`.
    `OpportunityDetector` forces one full (non-bailed) evaluation per triangle per
    `reject-sample-ms` window regardless, so `JOURNAL-BPS-ANALYSIS.md`'s paired
    `(gross_bps, net_bps)` drag diagnostic keeps appearing on the journal even though most ticks now
    skip the walk entirely; `cfarb_opportunities_detected_total`'s rate falls as a direct, expected
    consequence (it now counts only fully-evaluated candidates), not a feed regression.
  - **Phase 2 — dynamic per-tick sizing.** The operator's per-cycle notional cap
    (`cf-bot.risk.max-notional-usd` / a triangle's own override) was being used as the SIZING
    decision, not just a risk ceiling — but profit is `N × net(N)/10⁴`, and `N` is in the numerator.
    `profit(N) = finalAmount(N) − N` is concave in the depth dimension (deeper ladder levels price
    worse), so its maximiser sits at a ladder-level boundary. New pure helper
    `Sizer.candidateInputs` enumerates up to 8 candidate sizes at leg 0's ladder boundaries
    (notional cumulative for an ASK leg 0, quantity cumulative for a BID leg 0 — today every
    configured triangle's leg 0 is ASK, but `Triangle` does not guarantee it), always clamped to and
    including the cap itself. New `EdgeCalculator.evaluateBestSize` runs the same size-independent
    gross pass and early-out, then walks the existing fill-in-full ladder logic once per candidate
    into a preallocated scratch `Result`, keeping the one maximising absolute profit — **never
    bps**. `OpportunityDetector` now calls this instead of the fixed-size entry point; the chosen
    notional (`out.legInputAmount[0]`), not the cap, flows into the `OrderIntent` and the journal's
    `notional_usd`. **Can only size DOWN** from `min(equity, cap)` — the S6 ceiling is untouched.
    **Fill-in-full stays** (non-negotiable #10): every candidate is still evaluated through
    `Sizer.fillLeg`, which still rejects any size the ladder can't absorb in full — this picks among
    fully-fillable sizes, never partial-fills. **Fails closed:** no fillable candidate anywhere in
    the search means `fillable = false`, same as before. `opportunity` journal events gained
    `size_candidates` (how many sizes were walked) and `net_bps_at_cap` (what the OLD fixed-size
    path would have produced sizing at the cap) — the A/B measurement that makes the "~2.8× more
    profit per trade by optimising dollars instead of bps" claim (`JOURNAL-BPS-ANALYSIS.md` §14.1)
    verifiable from a live capture, not just a two-point model fit. Both omitted when the search
    never ran (a bailed candidate, or a line from the plain fixed-size `evaluate`).
  - **Cross-check protected structurally, not by convention:** `EdgeCalculator.evaluate(triangle,
    books, startAmount, out)` (the 4-arg entry point) is untouched; `evaluateBestSize` is additive
    and calls into the same gross/pass-2 arithmetic. `EdgeCalculatorTest` adds a consistency
    invariant — a direct `evaluate(...)` call at the notional `evaluateBestSize` chose must produce
    an identical `Result` field-by-field — proving the search returns a real evaluation, not a
    differently-computed one. `cf-arb-bot-plan.md` §5.3.1 was amended (a separate PR against
    `cf-trader`) before this landed, per non-negotiable #7.
  - **Known interaction, not a defect:** when `cf-bot.journal.reject-sample-ms ≤ 0` (sampling
    disabled — "only sane for short local captures", per `EventJournal`'s own existing caveat), the
    per-triangle diagnostic-forcing check (`nowNanos - lastFullEvalNanos ≥ rejectJournalIntervalNanos`
    with the interval at `0`) is unconditionally true, so every tick fully evaluates and Phase 1's
    gross early-out never actually bails in that mode. Harmless (strictly more work, never wrong),
    but worth knowing before using `reject-sample-ms=0` to characterize Phase 1's CPU savings.
- **Per-stage latency histograms are still blended**: `decisionToLeg1AckNanos` records the FULL
  place→reconcile→(commission-lookup) round trip for every leg into one histogram, not separate
  receipt→decode / decode→decision / queue-wait / leg-ack stages (Tier 3).
- **Zero-copy decode remains unimplemented** — `feed.MexcWsClient`'s `full.getBytes()` still copies
  every frame (rule R1 violation), measured at p50=5.7µs against a ~230ms-plus REST budget (now
  higher still: the real wire contract requires a place-then-query-then-trades round trip per leg,
  not the single call originally assumed). Deferred deliberately; recorded in `RUST-MIGRATION.md`.
- `journal.EventJournal` does not sync to S3 yet (needs the AWS SDK + a real bucket to test against).
  **Third-pass review:** the local reject stream is now SAMPLED — `cf-bot.journal.reject-sample-ms`
  (default 1000) bounds journaled REJECT events to one per triangle per interval, with suppressed
  ones counted as `cfarb.journal.suppressed`. Unsampled it ran at feed rate (~900 lines/s, ~10 GB/day,
  filling the deployed 20 GB root volume in ~2 days of DRY-RUN) and put ~900 `String` allocations/s
  on the Netty event-loop thread, which rule R1 forbids. Retention is a `find`-based systemd timer,
  not logrotate: `EventJournal` names each file for its hour, so every file is a distinct logrotate
  logfile whose chain never advances — `rotate 7` could not delete anything.
- `terraform/` has never been `apply`'d — no AWS credentials existed in the environment that wrote
  it. Validated with `terraform validate` only (both `terraform/` and `terraform/modules/probe/`
  currently pass cleanly).
- Gate 0 step 1 (the actual Tokyo/Singapore RTT probe) has not run — same reason.
- `/api/v1/opportunities`, `/api/v1/cycles`, and several `/api/v1/state`/`/api/v1/triangles` fields
  from plan §8 (per-symbol book age, uptime, per-triangle last-fire/cumulative-PnL) are not yet
  implemented.
- **Universe expanded 2026-09-10** (`usdt-sol-btc-*`, `usdt-eth-btc-*` + SOLUSDT/SOLBTC/ETHBTC
  symbols) after a 20 h dry-run at the marginal 10-triangle universe produced zero fires, and
  `cf-arb-poc`'s own triangular results placed every profitable MEXC cycle in the SOL/ETH-BTC family
  (excluded at $100 for SOLBTC's 1-whole-SOL minimum — a seed-size call, not a permanent one). These
  cost 15 bps taker round-trip; non-negotiable #7's "$100 seed refuses SOLBTC" is unchanged — the
  refusal is `EdgeCalculator` arithmetic, still live, just no longer the whole story at a larger seed.
  The S6 notional ceiling that used to be a frozen `RiskGates.ABSOLUTE_MAX_NOTIONAL_USD = 1000.0`
  (and silently clamped every cycle to $1k at a 4-5 figure seed) is gone: `cf-bot.risk.max-notional-usd`
  is now the single notional cap, trusted as configured, and only a non-positive value fails the boot.
  See non-negotiable #3.
- **`opportunity` journal events now carry `gross_bps` alongside `net_bps`** (`EdgeCalculator.Result#grossBps`):
  the top-of-book cyclic edge before depth/quantization, i.e. exactly what `cf-arb-poc`'s
  `stage2_cycles.evaluate_cycle` reports as its per-tick `net_bps`. Populated even on `unfillable`
  rejects, so a dry-run journal separates "no edge" from "edge present but un-fillable / slippage-eaten"
  and is directly comparable to `gate0_rebaseline`. Computed on the Netty thread but allocation-free
  (3 divisions); it is a diagnostic, never an input to the fire decision. **2026-09-10
  (JOURNAL-TUNING-TASK T2/T4):** the sampled reject is now the window's BEST candidate (not its
  first), the line carries `sampled_from`, and both sampled rejects and fires carry per-leg
  `leg_top_px`/`leg_touch_qty`/`leg_worst_px`/`leg_base_qty`.

## Threading model quick reference

| Thread | Owns | Never |
|---|---|---|
| Netty event loop (`feed.MexcWsClient`) | decode → book update (incl. the JOURNAL-TUNING T1c crossed-latch `L2Book.reset()` — the book's own thread, not a hand-off) → `strategy.OpportunityDetector` | allocate on the steady path, log per-tick, block |
| `cf-arb-executor` (`exec.CycleExecutor`) | 3 sequential legs, each place→query→cancel-if-non-terminal→(trades-reconcile), `exec.Unwinder` (reads `book.L2Book`'s published top-of-book to price an emergency reversal — declared exception, see `L2Book`'s javadoc) | touch a book for anything but reading published top-of-book; make a trading DECISION from it |
| `cf-arb-journal-writer` (`journal.EventJournal`) | NDJSON append, optional console echo of each appended line (`cf-bot.observability.echo-events`) | backpressure the hot path — drop and count instead |
| Quarkus HTTP worker (`api.BotApiResource`, `api.ReadinessCheck`) | read-only JSON | any write to trading state (S12) |
| Vert.x periodic timers (`BotService`: warm-up/clock-skew, feed watchdog, latency snapshots, opt-in console activity report) | read-only diagnostic reads, `killSwitch.recordFeedUnhealthy`, the JOURNAL-TUNING T1b crossed-book WARN + `book_reset` journal event (observes `L2Book.crossedResetCount()`, never calls `reset()` itself) | gate or influence a trading decision directly — these feed the kill switch, journal and console only |

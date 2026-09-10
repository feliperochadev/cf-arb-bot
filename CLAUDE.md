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
3. **A mis-sized notional cap FAILS THE BOOT — it is never silently clamped** (S6).
   `cf-bot.risk.max-notional-usd` bounds every cycle. `cf-bot.risk.absolute-max-notional-usd` is an
   optional operator-declared hard ceiling; when set, `RiskGates` (`failStartup`) logs an ERROR and
   refuses to start if `max-notional-usd` exceeds it, or if either value is non-positive. No code
   constant, no clamping — a fat-fingered cap is loud and fatal, not quietly reinterpreted. (Until
   2026-09-10 this was a frozen `RiskGates.ABSOLUTE_MAX_NOTIONAL_USD = 1000.0` that clamped instead,
   which silently strangled every cycle to $1k once the seed moved to 4–5 figures.)
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
  (and silently clamped every cycle to $1k at a 4-5 figure seed) is now the optional
  `cf-bot.risk.absolute-max-notional-usd` — and it FAILS THE BOOT rather than clamping when
  `max-notional-usd` exceeds it. See non-negotiable #3.
- **`opportunity` journal events now carry `gross_bps` alongside `net_bps`** (`EdgeCalculator.Result#grossBps`):
  the top-of-book cyclic edge before depth/quantization, i.e. exactly what `cf-arb-poc`'s
  `stage2_cycles.evaluate_cycle` reports as its per-tick `net_bps`. Populated even on `unfillable`
  rejects, so a dry-run journal separates "no edge" from "edge present but un-fillable / slippage-eaten"
  and is directly comparable to `gate0_rebaseline`. Computed on the Netty thread but allocation-free
  (3 divisions); it is a diagnostic, never an input to the fire decision.

## Threading model quick reference

| Thread | Owns | Never |
|---|---|---|
| Netty event loop (`feed.MexcWsClient`) | decode → book update → `strategy.OpportunityDetector` | allocate on the steady path, log per-tick, block |
| `cf-arb-executor` (`exec.CycleExecutor`) | 3 sequential legs, each place→query→cancel-if-non-terminal→(trades-reconcile), `exec.Unwinder` (reads `book.L2Book`'s published top-of-book to price an emergency reversal — declared exception, see `L2Book`'s javadoc) | touch a book for anything but reading published top-of-book; make a trading DECISION from it |
| `cf-arb-journal-writer` (`journal.EventJournal`) | NDJSON append, optional console echo of each appended line (`cf-bot.observability.echo-events`) | backpressure the hot path — drop and count instead |
| Quarkus HTTP worker (`api.BotApiResource`, `api.ReadinessCheck`) | read-only JSON | any write to trading state (S12) |
| Vert.x periodic timers (`BotService`: warm-up/clock-skew, feed watchdog, latency snapshots, opt-in console activity report) | read-only diagnostic reads, `killSwitch.recordFeedUnhealthy` | gate or influence a trading decision directly — these feed the kill switch, journal and console only |

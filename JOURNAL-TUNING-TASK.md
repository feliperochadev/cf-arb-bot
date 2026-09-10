# Task: act on the journal analysis — observability defects first, then tuning

**For:** a follow-up agent, on its own branch (suggest `feat/journal-tuning-p0`).
**Status:** spec only — no code in this repo has been changed for it.
**Source:** `JOURNAL-BPS-ANALYSIS.md` (191,548 journal events, 2026-09-09→10). Read that for the
*why*; this doc is the *what* and *how*. Section references below (§3, §5.5, …) point into it.

## Objective

The analysis found one defect and four telemetry gaps that make further tuning unmeasurable, plus a
set of parameter changes. **Do the defect and the telemetry first** — every tuning number in the
analysis is currently an inference from rows that were *not* written, and one triangle family has
been effectively unobserved. Tuning on top of that is guesswork.

Tasks are ordered. **T1–T4 are P0 and should land together as one PR.** T5–T8 are P1. T9–T11 are P2
and several are operator decisions, not code.

## Hard constraints — do not change

- `cf-bot.dry-run=true` (CLAUDE.md non-negotiable #1: the default never changes in code).
- Any `cf-bot.risk.*` cap, `cf-bot.capital.*`, `cf-bot.strategy.min-net-bps`,
  `cf-bot.exec.order-type`. These are **operator** config (non-negotiable #3). T5 and T9 below
  *recommend* values; they do not authorize an agent to set them. Propose, don't apply.
- No new thread hand-off (non-negotiable #5). Everything here runs on an existing thread: the Netty
  event loop, the journal writer, a Vert.x timer, or a Quarkus HTTP worker.
- No allocation on the steady-state tick path (rule R1). T2 and T4 both touch the hot path — see
  their notes.
- `EdgeCalculator` arithmetic is not in scope. If any task appears to need it, stop and flag
  (non-negotiable #7 requires a cross-check against `cf-arb-poc/cfarb/stage2_cycles.evaluate_cycle`).
- `Sizer`'s fill-in-full policy stays (non-negotiable #10). T8 asks for a *smaller request*, never a
  partial fill.

---

# P0 — the defect and the instruments

## T1. `BTCUSDT`'s book latches into an unusable state and never recovers

**Evidence** (`JOURNAL-BPS-ANALYSIS.md` §5.5): the five triangles containing `BTCUSDT` were
evaluable **1.6%** of a clean sample hour; they go dark together (Jaccard 85–96% within the group,
1.4–1.7% against every other triangle); in **17 of 17 sessions** they die once and never recover
until restart (survival 10 s – 3.4 h); and `GET /api/v1/triangles` confirms `booksReady:false` on
exactly those five, live.

**Narrowed cause:** `booksReady` is `isTrusted() && !isEmpty() && !isCrossed()` and excludes the age
check, so this is not staleness. `isTrusted()` is eliminated (`books 30/30 warm` in the activity
report). `isEmpty()` is implausible under continuous frames. That leaves **`isCrossed()`**, which
has **no self-healing path** — nothing in `L2Book.apply()` removes crossing levels, and `reset()`
only fires on a version-chain gap. A one-way latch is exactly what the session data shows.

**Hypothesis for *why* `BTCUSDT`:** `L2Book` prunes to `PRUNE_KEEP = 200` levels per side. At a 0.01
tick that spans ~$2.00 ≈ 1.7 bps of a $120k price. If `aggre.depth@10ms` does not deliver an explicit
`qty=0` for every level leaving the touch during a fast move, stale far-side levels survive at index
0 and the book crosses. This contradicts `L2Book`'s own javadoc invariant ("deletions arrive
explicitly as qty=0, so it never contains a stale level"), inherited from `cf-arb-poc/cfarb/book.py`
which predates the Gate 0 switch to `aggre.depth@10ms`.

### T1a. Observe it (do this first — it is minutes of work and it settles the cause)

Add per-symbol book state to `api.BotApiResource`. New `GET /api/v1/books` (or a `books` block on
`/api/v1/state` — plan §8 already lists "per-symbol book age" as an unimplemented field):

```json
{ "BTCUSDT": { "trusted": true, "crossed": true, "empty": false, "ageMs": 12,
               "updateCount": 481203, "bidCount": 200, "askCount": 200,
               "topBid": "120431.55", "topAsk": "120429.10", "spreadBps": -0.20 } }
```

- Read-only, on the Quarkus HTTP worker thread (CLAUDE.md threading table: "read-only JSON, never a
  write to trading state"). Same advisory cross-thread read `booksReady` already performs; no new
  hand-off.
- A negative `spreadBps` is the smoking gun. Capture the output while the bot is in the latched
  state and record it in the PR.

**Acceptance:** endpoint returns all 30 symbols; running against a live dry-run reproduces the
latch and identifies which predicate is false for `BTCUSDT`.

### T1b. Count it and alert on it

- `BotMetrics`: `cfarb.book.crossed{symbol}` gauge (or a counter of crossed→clean transitions) and
  `cfarb.book.reset{symbol,reason}`.
- One `WARN` when a book first goes crossed, with symbol, top bid/ask and `updateCount`. **Rate-limit
  it** — this is the Netty thread; a per-symbol "already warned" flag, no per-tick logging (R1).

**Acceptance:** the latch is visible in `/q/metrics` and produces exactly one WARN per episode.

### T1c. Make it self-heal

A book that has been crossed for longer than a short grace period (suggest `500 ms`, config
`cf-bot.book.max-crossed-ms`, fail-closed default) must `reset()` and re-warm rather than latch. A
crossed book is currently a permanent, silent, per-symbol outage.

- `reset()` already exists and already clears `trusted`, so the book fails closed while re-warming —
  no risk of trading off a half-rebuilt ladder (rule S5).
- Where to trigger: on the Netty thread inside `apply()`/`maybePromote()`, or on the existing feed
  watchdog Vert.x timer (which already does `books.resetAll()` — see `BotService.decideWatchdogAction`
  and `BotServiceWatchdogTest`). **Prefer the watchdog timer**: it keeps the check off the tick path
  and reuses a mechanism that already has tests.
- Emit a `feed_reconnect`-style journal event or a dedicated `book_reset` event so a capture records
  it.

**Acceptance:** a unit test drives a book into a crossed state, advances the clock past the grace
period, and asserts `reset()` was called and `isTrusted()` is false; the BTC triangles recover
mid-session in a live dry-run.

### T1d. Regression-test `L2Book` against real frames

Replay a captured `BTCUSDT` sequence through `L2Book` and assert it never crosses. Fixtures live in
`src/test/resources/fixtures/` (`mexc_depth_*.pb.bin` + `mexc_depth_oracle.json`); a longer
`BTCUSDT`-specific capture can be extracted from `recorder-service/data/mexc/`.
**Never a live network call in a test (rule Q10 / non-negotiable #8.)**

**Acceptance:** a test that fails on today's `L2Book` if the pruning hypothesis is right, and passes
after the fix. If it does *not* fail, the hypothesis is wrong — say so in the PR and fall back to
T1c (self-heal) as the mitigation, which works regardless of cause.

### T1e. Only then, the real fix

Decide from T1a/T1d evidence between: a wider retained ladder; pruning by **price distance** from the
touch rather than by level count; or a periodic forced re-sync. Do not pick one before T1d exists.

> **Note for whoever picks this up:** `usdt-btc-usdc-fwd` has the best raw-dislocation profile of any
> deep triangle in the capture (positive **43.7%** of the time, p99 **+3.94 bps**) and has been
> measured on ~3% of its intended sample. Do **not** prune the BTC family as "dead" (§6 caveat) until
> this task lands and a clean capture exists.

## T2. Journal the *best* reject per window, not the first

`OpportunityDetector.journalReject` keeps the **first** reject in each `reject-sample-ms` window and
suppresses the rest. Live metrics: `cfarb_journal_suppressed_total` 2,633,382 vs
`cfarb_opportunities_detected_total` 2,713,294 — **97.1% of evaluations are discarded, and the
retained 2.9% is a uniform sample, so every peak is invisible.** The tail is the only part that
matters for tuning.

**Change:** track a per-triangle running best within the window (highest `netBps`; for `unfillable`,
highest `grossBps`) and emit *that* at window close.

- Same line count, same file size — this is not a volume change.
- **Hot path (R1):** keep the pending best in existing primitive arrays
  (`double[] bestNetBps`, `double[] bestGrossBps`, `long[] bestNotional`, `int[] bestReason`
  alongside the existing `long[] lastRejectJournalNanos`). No object per candidate, no `String`
  formatting until the write actually happens.
- Emission is edge-triggered by the next candidate after the window expires — same as today. A
  triangle that goes quiet may hold its last best unemitted; that is acceptable, but add a
  `sampled_from` count to the event so the reader knows how many ticks it summarises.

**Acceptance:** unit test showing that given N candidates inside one window, the emitted event
carries the max, not the first, and that `sampled_from == N`. `cfarb.journal.suppressed` keeps
counting (recorder-service non-negotiable #2: a dropped frame that isn't counted is a lie).

## T3. Count stale-leg skips

`OpportunityDetector.evaluate` returns silently when `allLegsFresh()` fails. It is the single most
common outcome in the process and there is no counter for it — §5's entire duty-cycle analysis had
to be reconstructed from absent rows.

**Change:** `cfarb.detector.stale_skip{triangle}` counter, and — cheaply — a
`cfarb.detector.stale_skip_leg{symbol}` counter incremented for the *first* failing leg, which
attributes the blockage to a symbol directly instead of by inference.

**Acceptance:** duty cycle readable straight off `/q/metrics`; the BTCUSDT attribution from §5.5
reproduces as a counter rather than a Jaccard argument.

## T4. Log per-leg depth on `opportunity` events

> **Priority raised after §8.2.** This is now the single highest-value telemetry item in the
> document, ahead of T2. The scenario analysis in §8.2 (P0 fix + 2000 ms + $100 + MX −20% + gate 4.0)
> produced an expected **$2.63/day**, of which **99.2% comes from one triangle** whose 13-minute
> "edge" shows every sign of being a displayed-but-unfillable quote (`raw` p50 **+6.81 bps** held for
> 13 minutes on a ~$0.00 M/24 h book, against `drag` p50 **36 bps** at $10k). Whether that edge is
> executable at *any* size is the question the whole profitability estimate turns on, and it **cannot
> be answered from the current journal**. T4 is what answers it.

The most important open question — *what notional maximises `net_bps` on this book right now?* —
cannot be answered offline because the events carry no size information. This blocks T8 entirely and
makes §3's `$100` counterfactual an extrapolation rather than a measurement.

**Additional requirement from §8.2:** also record **per-leg book age at decision time** (ns since
that book's last update). Two separate findings need it — the T6 selection-effect question, and
distinguishing a real dislocation from a stale quote on a thin cross leg. Both are currently
unanswerable.

**Change:** add to the `opportunity` event, per leg: top-of-book price, **available quantity at the
touch**, and the worst price the walk reached. `EdgeCalculator.Result` already carries
`legWorstPriceFixed[]` and `legBaseQtyFixed[]`; `L2Book` already exposes `bestBidQty()`/`bestAskQty()`.

- **R1:** this widens the JSON built in `JournalEvents.opportunity`, which already allocates a
  `String` — but only on the sampled path, which after T2 is ~1 event/triangle/second. Do not
  compute or format anything for suppressed candidates.
- Keep it a flat array of numbers, not nested objects, to bound the line size.

**Acceptance:** a dry-run journal line carries all three per-leg values; a notebook can compute
"largest notional that stays inside the touch" for every sample without re-running the bot.

---

# P1 — the tuning that the P0 work makes measurable

## T5. Notional: propose, don't apply

§3 measured drag scaling ~linearly with size (median **3.5 bps @ $1k → 8.1 bps @ $10k**, up to
**62 bps** on thin cross legs), and showed the best episode in the capture going from **−19.6 bps at
$10k to +0.7 bps at $100**.

`cf-bot.risk.max-notional-usd` is an operator cap (non-negotiable #3). **The agent's deliverable is
a recommendation in the PR body, not an edit**: suggest **$500–$1,000** for the next capture, with
§3.1's table as justification, and let the operator set it.

What the agent *may* implement is the mechanism: per-triangle
`cf-bot.triangles.<name>.max-notional-usd`, defaulting to the global cap, so `XRPETH` can be given
$200 while `USDCUSDT` keeps the full cap. Same fail-the-boot validation as the global cap
(`RiskGates.failStartup` on a non-positive value) — never a silent widening.

**Acceptance:** boot fails loudly on a non-positive per-triangle cap; absent key inherits the global
cap; `OpportunityDetector.evaluate`'s `candidateNotional` becomes
`min(eligibleBalance, triangleCap)`.

## T6. Per-symbol `max-book-age-ms`

§5.4: `usdt-usdc-usd1` — the best-priced triangle on the venue (`net_bps` p99 **−1.2 bps**, drag
1.0 bps, zero fees) — is evaluable **11%** of the time because `USD1USDC` quotes roughly every 2.3 s
against a global 250 ms freshness gate. Projection at 2000 ms: **61–88%** duty, a 5.5–8× increase.

**Change:** `cf-bot.symbol-overrides.<SYMBOL>.max-book-age-ms`, defaulting to
`cf-bot.strategy.max-book-age-ms`. Raise to ~2000 ms **only** for pegged/stable/gold books
(`USD1USDC`, `USD1USDT`, `USDCUSDT`, `GOLD(XAUT)*`, `TAOUSDC`).

**Constraints:**
- This relaxes a **fail-closed** gate (rule S5). It must be per-symbol and explicit — never a global
  widening — and a symbol with no override must behave exactly as today.
- Log every active override at startup, at WARN, naming the symbol and the value, so an operator
  reading the boot log sees which books are being trusted longer.
- Do **not** raise it for a major. `BTCUSDT` never earns more than 250 ms.
- **Do not raise it for a thin cross leg** (`XRPETH`, `XRPBTC`, `SOLBTC`, `ETHBTC`, `TAOUSDC` as a
  *cross*). §8.2: the one apparent edge in the capture large enough to clear a 4 bps gate was a
  **+6.81 bps median dislocation sustained for 13 minutes** on `XRPETH` (~$0.00 M/24 h) — a pattern
  that cannot be a live arbitrage (it would have been taken) and is far more consistent with a quote
  that is displayed but not fillable. A 2 s freshness window admits **more** of exactly that. The
  justification for 2000 ms is "this asset's price genuinely does not move" (true of a peg), never
  "this book is quiet".
- Sizing note: raising the window does **not** help the triangle that dominates the §8.2 estimate —
  `usdt-eth-xrp` already ran at ~90% duty, so 2000 ms adds ~11% more samples there, not 8×. The real
  beneficiary is `usdt-usdc-usd1` (11% → 61–88%).

**Open question the implementer should record, not resolve:** §5.4 flags a possible selection effect
— a thin book only ticks when something changes, so post-wake-up samples may be systematically
different. T4's per-leg book age is what makes this testable; note it in the PR as unresolved.

## T7. `config_snapshot` startup event

Era boundaries in the 24 h capture had to be reverse-engineered from `notional_usd` changing
mid-stream. Emit one journal event at startup carrying: dry-run, seed, anchor, all `risk.*` caps,
`min-net-bps`, `slippage-buffer-bps`, `max-book-age-ms`, `reject-sample-ms`, order type, symbol count,
triangle count, and a hash of the loaded fee table. **No secrets** (S1–S3 — there are none in
`BotConfig` by construction, but do not add any).

**Acceptance:** every future capture is self-describing.

## T8. `Sizer` solves for the net-maximising notional

The real fix behind T5. `Sizer` already walks the ladder; have it return the notional that maximises
`net_bps` (in practice the touch-bounded size, subject to `min_notional`) instead of only accepting
the size it is handed.

**This is the largest change in this document and it is gated:**
- It changes what the bot trades, so `cf-arb-bot-plan.md` §5.3 must be updated **first**.
- It borders `EdgeCalculator` (non-negotiable #7). If the arithmetic changes at all, a cross-check
  against `cf-arb-poc/cfarb/stage2_cycles.evaluate_cycle` is mandatory (`EdgeCalculatorTest`).
- It must **not** relax fill-in-full (non-negotiable #10). The change is "ask for less", never
  "accept a partial fill".
- Hot path: any search must be bounded and allocation-free — a fixed, small set of candidate sizes
  evaluated in a loop over primitive locals, not a growing collection.

**Do not start T8 before T4 has produced a capture with per-leg depth.** Without it there is no data
to validate the chosen size against.

---

# P2 — operator decisions and universe changes

## T9. Fee model refresh (operator purchase + config)

`mexc_filters.json` assumes undiscounted 5.0 bps taker. MEXC's MX-holding discount changes that.
**Verify the actual discount tier before relying on any number** — `MEXC-PAIR-EXPANSION.md` §2 states
50%, and the figure has also been discussed as 20%; these give very different answers (§8.1 of the
analysis). Once confirmed and the tokens are held, refresh `taker_bps`/`maker_bps` and re-run the
analysis.

**Also record the risk in the other direction:** the 0.0 bps taker on every USDC/USD1 pair is a
promotion with no announced end date. If it ends, the only two all-zero-fee triangles silently become
10–15 bps triangles and keep evaluating. A live cutover needs a fee-refresh mechanism; a note already
exists in `mexc_filters.json`.

## T10. Triangle universe

- **Safe to disable now:** `usdt-sol-btc-rev` (raw p50 **−69 bps**, drag p50 **147 bps** — cannot
  fire at any threshold, size or latency) and `usdt-usd1-xaut-fwd` (40% duty, raw p99 −10.27,
  honestly measured).
- **Hold** on the rest of the BTC family until T1 lands — see the note under T1e.
- **Consider adding** `XRPUSD1` → `usdt-xrp-usd1` (1 bp total round trip: `XRPUSDT` 0 + `XRPUSD1` 1 +
  `USD1USDT` 0, with two deep legs — `MEXC-PAIR-EXPANSION.md` §4b rank 3). Follow
  `MEXC-SYMBOL-EXPANSION-TASK.md`'s process: re-fetch `exchangeInfo`, add the `mexc_filters.json`
  row, wire fwd+rev, enabled for dry-run only.
- The universe is at **30/30** symbols (`MexcProtocol.MAX_STREAMS_PER_CONNECTION`). Adding `XRPUSD1`
  requires dropping a Group C observation-only symbol first, or a second connection.

## T11. Threshold — last, not first

§8: between a 6.0 and a 1.0 gate the count of qualifying samples goes 0 → 2. **The distribution has
no mass there; lowering `min-net-bps` alone accomplishes nothing.** Re-derive
`slippage-buffer-bps` from measured leg-failure cost once real fills exist — note that `net_bps` is
already computed at the worst ladder price touched and `CycleExecutor` submits at exactly that
boundary, so the buffer may be double-counting. Both values are operator config: **propose, don't
apply**, and only after T1/T5/T6 have landed and a clean capture has been re-measured.

---

## Verification checklist

- [ ] `./mvnw test` green, including new tests for T1c, T1d, T2.
- [ ] Dry-run boot clean: no `RiskGates.failStartup`, all symbols warm, all triangles load.
- [ ] `/api/v1/books` (T1a) reproduces the `BTCUSDT` latch and names the failing predicate — capture
      the JSON in the PR.
- [ ] After T1c: a multi-hour dry-run in which the BTC-family triangles **recover mid-session** at
      least once. This is the single acceptance test that matters.
- [ ] After T2: journal lines carry `sampled_from`, and the observed `net_bps` maximum for a busy
      triangle is measurably higher than the same window under first-sample sampling.
- [ ] After T3: duty cycle readable from `/q/metrics` and consistent (±5 pp) with the journal-derived
      figures in §5.3.
- [ ] Re-run the analysis in `JOURNAL-BPS-ANALYSIS.md` Appendix A against a fresh ≥6 h capture with
      no restarts, and update the document's tables. **Several of its conclusions are expected to
      move once the BTCUSDT family is actually observable.**
- [ ] No new thread appears in a thread dump beyond the four in CLAUDE.md's threading table.

## Out of scope

Live trading (`dry-run` stays `true`), `EdgeCalculator` arithmetic, `UserDataStream` verification,
anchor-asset / USDC-anchored cycle work, S3 journal sync, the Rust migration, and any second-venue or
futures work. §7 of the analysis shows latency is **not** the constraint (p50 4.4 µs frame→decision;
positive-edge episodes persist up to 48 s) — do not spend effort there.

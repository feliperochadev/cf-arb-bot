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
3. **Hard caps are enforced in code, not only config** (S6). `RiskGates` clamps
   `max-notional-usd` to an absolute ceiling regardless of what config says, and flags it at
   startup if clamping occurred.
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

- **`cf-bot.exec.order-type` (`IMMEDIATE_OR_CANCEL`) is not currently supported by MEXC for ANY of
  the 9 configured symbols** — confirmed live against `GET /api/v3/exchangeInfo` 2026-09-07; every
  symbol advertises only `LIMIT`/`MARKET`/`LIMIT_MAKER`. `BotService` validates this at startup and
  fails closed in live mode (S5) until either MEXC enables it here or the execution design changes
  around a resting `LIMIT`/`LIMIT_MAKER` order. This is the actual remaining blocker on live mode,
  not a code defect — see `cf-arb-bot-review-plan.md` Tier 1 step 1.3.
- `exec.UserDataStream` is unverified against the live listenKey endpoint (Phase 1 TODO).
- **No real balance reconciliation on boot** — `state.Portfolio` is a single in-memory number seeded
  from `cf-bot.capital.seed-usd`; a restart does not fetch or reconcile the live account, and
  non-anchor inventory left over from an interrupted cycle is invisible to it. Required before any
  unattended live run (`cf-arb-bot-review-plan.md` Tier 3).
- **Unwind reversal pricing uses each leg's own stale detection-time price**, not a current book —
  `exec.Unwinder`'s javadoc documents this explicitly. Pricing from a live book needs either a
  published top-of-book snapshot from the feed thread or a REST book fetch, neither of which exists
  yet on the executor thread (Tier 3).
- **Per-stage latency histograms are still blended**: `decisionToLeg1AckNanos` records the FULL
  place→reconcile→(commission-lookup) round trip for every leg into one histogram, not separate
  receipt→decode / decode→decision / queue-wait / leg-ack stages (Tier 3).
- **Zero-copy decode remains unimplemented** — `feed.MexcWsClient`'s `full.getBytes()` still copies
  every frame (rule R1 violation), measured at p50=5.7µs against a ~230ms-plus REST budget (now
  higher still: the real wire contract requires a place-then-query-then-trades round trip per leg,
  not the single call originally assumed). Deferred deliberately; recorded in `RUST-MIGRATION.md`.
- `journal.EventJournal` does not sync to S3 yet (needs the AWS SDK + a real bucket to test against).
- `terraform/` has never been `apply`'d — no AWS credentials existed in the environment that wrote
  it. Validated with `terraform validate` only (both `terraform/` and `terraform/modules/probe/`
  currently pass cleanly).
- Gate 0 step 1 (the actual Tokyo/Singapore RTT probe) has not run — same reason.
- `/api/v1/opportunities`, `/api/v1/cycles`, and several `/api/v1/state`/`/api/v1/triangles` fields
  from plan §8 (per-symbol book age, uptime, per-triangle last-fire/cumulative-PnL) are not yet
  implemented.

## Threading model quick reference

| Thread | Owns | Never |
|---|---|---|
| Netty event loop (`feed.MexcWsClient`) | decode → book update → `strategy.OpportunityDetector` | allocate on the steady path, log per-tick, block |
| `cf-arb-executor` (`exec.CycleExecutor`) | 3 sequential legs, each place→query→(trades-reconcile), `exec.Unwinder` | touch a book directly |
| `cf-arb-journal-writer` (`journal.EventJournal`) | NDJSON append | backpressure the hot path — drop and count instead |
| Quarkus HTTP worker (`api.BotApiResource`, `api.ReadinessCheck`) | read-only JSON | any write to trading state (S12) |
| Vert.x periodic timers (`BotService`: warm-up/clock-skew, feed watchdog, latency snapshots) | read-only diagnostic reads, `killSwitch.recordFeedUnhealthy` | gate or influence a trading decision directly — these feed the kill switch and journal only |

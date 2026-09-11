# Task: suppress duplicate fires on unchanged liquidity ("Fix A")

**For:** a follow-up agent, on the current branch (`feat/journal-tuning-p0`).
**Status:** spec only — no code in this repo has been changed for it.
**Source of the finding:** `JOURNAL-BPS-ANALYSIS.md` §12–§15 (live dry-run capture, 2026-09-10).

## Objective

The detector can fire the same opportunity repeatedly because nothing tells it that the liquidity it
is about to trade is the liquidity it just traded. Give each fire a **signature** derived from the
book levels the order would actually consume, and refuse to re-fire a triangle while that signature
is unchanged.

**Hard constraints — do not change:** `cf-bot.dry-run`, any `cf-bot.risk.*` cap,
`cf-bot.strategy.min-net-bps`, `cf-bot.exec.order-type`, or the universe. No new thread hand-off
(non-negotiable #5). No allocation on the steady-state tick path (rule R1) — every structure below is
a preallocated primitive array. `EdgeCalculator`'s arithmetic is untouched (non-negotiable #7); this
task only *reads* values it already computes.

---

## Why — the evidence

At **22:42:12 UTC on 2026-09-10** the bot fired **5 cycles in 1.3 seconds**, all
`usdt-btc-usdc-fwd`, all $500:

```
22:42:12.047 FIRE  net=3.1002280000 gross=3.5533 N=$500
22:42:12.305 FIRE  net=3.1002280000 gross=3.5533 N=$500  (+247ms)
22:42:12.565 FIRE  net=3.1002280000 gross=3.5533 N=$500  (+258ms)
22:42:12.817 FIRE  net=3.1002280000 gross=3.5533 N=$500  (+252ms)
22:42:13.352 FIRE  net=3.1002280000 gross=3.3827 N=$500  (+534ms)
```

`detected_net_bps` identical to **10 decimal places**, `realized_pnl_usd` identical at $0.15501,
spaced at exactly `cf-bot.risk.cycle-cooldown-ms=250`. Note `gross` moved on the last one while `net`
did not — the ladder walk kept landing on the same quantized levels.

**That is one opportunity counted five times.** `RiskGates.claim()` (`risk/RiskGates.java:190`)
advances only a *time* cooldown (`lastFireNanosByTriangle`). In dry-run the paper fill never consumes
depth (`exec/CycleExecutor#executePaper`), so the book keeps offering the identical edge and
`EdgeCalculator` keeps recomputing identical `legWorstPriceFixed[]` / `legBaseQtyFixed[]`.

### The live-mode consequence is the real reason to fix it

Capital does **not** multiply — the executor is single-threaded (`cf-arb-executor`) and a triangular
cycle is self-liquidating. What happens instead is **self-inflicted adverse selection**: cycle 2
fires 250 ms later on a book that still shows liquidity cycle 1 already took (MEXC's depth push lags
14–41 ms), prices leg 0 at a boundary that no longer exists, and either no-fills, partial-fills, or
strands inventory into `exec.Unwinder` at a 40 bps cross.

The payoff is asymmetric roughly **13:1**:

| | on $500 |
|---|---|
| winning cycle @ +3.10 bps | **+$0.155** |
| broken cycle @ 40 bps unwind | **−$2.00** |

Duplicates are *structurally more likely* to break than the original, because by definition they race
liquidity that is being consumed. Three consecutive failures also trips
`cf-bot.risk.max-consecutive-failures=3` and halts the bot.

`cf-bot.exec.max-intent-age-ms=150` is **not** sufficient protection: it only drops intents that
waited in the queue. When live cycle time (~230 ms) and the cooldown (250 ms) pipeline evenly, each
intent is dequeued at age ≈ 0 and every duplicate executes.

Related second-order costs: 5× taker fees to harvest one dislocation's edge, and ~45 REST calls in
1.3 s against MEXC's rate limits (`RiskGates` fails closed on unknown rate-limit state).

---

## Decisions already made — do not re-litigate

1. **Unconditional.** Applies in live *and* dry-run, not gated on `dryRun`. Live has the same race
   between a fill and the depth update reflecting it.
2. **Signature = `(worstPrice, baseQty, levelWriteSeq)` per leg.** The write stamp is what
   distinguishes "nobody touched this level" from "it was consumed and replenished".
3. **No `aggre.deals` subscription.** Considered and rejected: costs a subscription slot (the
   universe is at 30/30, `MexcProtocol.MAX_STREAMS_PER_CONNECTION`), adds a second protobuf decoder
   to the Netty thread, and non-negotiable #9 would require a live probe first.
4. **No expiry / TTL.** The signature is replaced only when a *different* one fires. The monotonic
   write stamp makes explicit clearing unnecessary — see "Why no reset handling is needed".

## Why a write stamp, and its documented blind spot

MEXC's public feed is **L2 aggregated** — quantity per price, no order IDs, no order count, no queue
position. Per `recorder-service`'s own M6 caveat, MEXC has **no individual trade channel at all**,
only `aggre.deals`. True order identity (L3) is unobtainable on this venue, so any signature is
inference. The write stamp gives price-*level* identity, which is the closest available:

- same `(price, qty, writeSeq)` → the level has not been rewritten since detection → same untouched
  resting liquidity → **suppress**
- `writeSeq` advanced → the venue rewrote the level → genuinely new liquidity → **allow the fire**

**Blind spot — document it in the code, do not engineer around it:** `aggre.depth@10ms` emits *net*
changes over a 10 ms window. A take-and-replace at exactly the same quantity inside one window
produces no diff, hence no stamp bump, hence a genuinely new opportunity is suppressed. That is a
**false negative** (one missed +$0.155). There is no symmetric false-positive path — a rewritten
level is always visible. Given the 13:1 asymmetry, the failure mode lands on the safe side by
construction.

---

## Implementation

### 1. `book/L2Book.java` — per-level write stamps

```java
private final long[] bidWriteSeq = new long[CAPACITY];
private final long[] askWriteSeq = new long[CAPACITY];
private long writeSeq;   // monotonic, NEVER reset -- see below
```

- In `applyLevel(...)`, stamp the touched level with `++writeSeq`.
- **The array shifts are the trap.** `applyLevel` has three branches and the stamp array must move in
  lockstep with `pxArr`/`qtyArr`:
  - **delete** (`qty == 0`, found): mirror the existing `System.arraycopy` shift-left.
  - **update in place** (found): overwrite `seqArr[idx]` — the load-bearing case.
  - **insert** (not found): mirror the shift-right, then stamp `seqArr[idx]`.
  `pruneIfNeeded` truncates by lowering the count only, so the stamp array needs no action there.
- `reset()` clears counts/prices as today but **must NOT reset `writeSeq`**.
- Accessors `public long bidWriteSeqAt(int i)` / `askWriteSeqAt(int i)`, mirroring the existing
  `bidPxAt`/`askPxAt` style.

**Why an internal `writeSeq` rather than the venue's `toVersion`:** `MexcDepthDecoder.DepthFrame`'s
`toVersion` is `-1` in synthetic frames (every unit test seeds it that way) and `apply()` already
guards `if (f.toVersion >= 0)`. An internal counter is always present, always monotonic, and
independent of venue version semantics. If a later change wants venue versions, substitute here —
nothing downstream cares.

**Why no reset handling is needed:** because `writeSeq` never resets, a book that resets and re-warms
(crossed-latch self-heal, `JOURNAL-TUNING-TASK.md` T1c) stamps its rebuilt levels with strictly
higher values than any signature the detector holds. The comparison naturally fails and the triangle
fires again. Resetting `writeSeq` would reintroduce a post-re-warm collision risk.

### 2. `strategy/Sizer.java` — carry the stamp out of the ladder walk

`Sizer.Result` gains `public long maxWriteSeq;`. In `fillAsk`/`fillBid`, accumulate

```java
maxWriteSeq = Math.max(maxWriteSeq, book.bidWriteSeqAt(i));   // or askWriteSeqAt
```

across **every level the walk touches**, not just the worst — a rewrite at any consumed depth must
invalidate the signature. Reset it to 0 at the top of `fillLeg` alongside the other outputs.

R1: one `long` compare-and-store per level already being visited. No allocation.

### 3. `strategy/EdgeCalculator.java` — surface it per leg

`Result` gains `public final long[] legWriteSeq = new long[3];`, populated from
`legResult.maxWriteSeq` in the existing second-pass loop, next to `legWorstPriceFixed` /
`legBaseQtyFixed`. Reusable output param, no allocation.

### 4. `strategy/OpportunityDetector.java` — the suppression check

Add flat arrays alongside the existing T2 `pending*` arrays:

```java
private final long[] lastFiredWorstPx;     // [nTri*3]
private final long[] lastFiredBaseQty;     // [nTri*3]
private final long[] lastFiredWriteSeq;    // [nTri*3]
private final boolean[] hasFiredSignature; // [nTri]
```

Place the check in `evaluate(...)` **immediately before `riskGates.claim(...)`** — after the
threshold test, before anything is claimed. This matches `RiskGates.canFire`'s documented
"non-claiming pre-check" contract: a suppressed duplicate must not burn cooldown or rate-limit
budget.

```java
if (hasFiredSignature[t] && all three legs match (worstPx, baseQty, writeSeq)) {
    metrics.recordDuplicateFireSuppressed(t);
    journalReject(t, tri.name(), true, edgeResult.netBps, edgeResult.grossBps,
                  candidateNotional, REASON_DUPLICATE, nowNanos);
    return;
}
// ... riskGates.claim(), build OrderIntent, orderQueue.offer() ...
// ONLY after offer() succeeds: store the new signature, set hasFiredSignature[t] = true
```

Storing the signature **only on a successful `offer`** matters: an intent that could not be enqueued
is rolled back via `riskGates.onCycleFinished()` and must stay re-fireable.

Detector-thread-only, 3 longs × 3 legs + 1 boolean per triangle, zero allocation. R1-clean.

### 5. `metrics/BotMetrics.java` — a counter

Follow the existing T3 pattern exactly (`detectorStaleSkipByTriangle`, `metrics/BotMetrics.java:43`):
a `Counter[] duplicateFireByTriangle` pre-resolved in `initRuntimeCounters(...)`
(`metrics/BotMetrics.java:114`) as `cfarb.detector.duplicate_fire{triangle=...}`, with a null-guarded
`recordDuplicateFireSuppressed(int triangleIndex)` so directly-constructed test instances no-op.

### 6. Journal — a new reject reason

Add `REASON_DUPLICATE` to the existing reason-code ints and `reasonString(...)` in
`OpportunityDetector`, emitting `"duplicate-signature"`. It rides the existing **sampled** reject
path, so it adds no journal volume, and it makes the suppression rate measurable offline rather than
only via Prometheus. `JOURNAL-BPS-ANALYSIS.md`'s Appendix A loader treats `reject_reason` as a free
string, so this is schema-compatible.

---

## Tests

Extend, don't replace — all **108** existing tests must stay green.

**`book/L2BookTest.java`**
- write seq advances when a level's quantity is updated in place
- write seq of an untouched level is unchanged after a *different* level is updated — this is the
  test that catches a broken arraycopy mirror
- write seq follows the correct level across an insert-shift and a delete-shift
- `reset()` does not lower `writeSeq`; levels rebuilt after a reset carry strictly higher stamps

**`strategy/OpportunityDetectorTest.java`** — mirror the existing harness (`seedBook`,
`SimpleMeterRegistry`, `@TempDir`, `awaitOpportunities`), and set `cycleCooldownMs = 0` so the time
cooldown cannot mask the behaviour under test:
- **fires once, suppresses the repeat**: `minNetBps` low enough to fire, seed a qualifying book, call
  `onBookUpdated` twice → exactly one `OrderIntent` in the queue; the second attempt journals
  `"duplicate-signature"`
- **re-fires when the level is rewritten**: re-apply the *same* price and quantity via `seedBook`
  (which calls `apply` → bumps the stamp) → two intents. **This is the test that proves the fix is
  more than price+qty equality.**
- **re-fires when price or size genuinely changes** → two intents
- **a failed `offer` does not store the signature**: full/zero-capacity queue → the next identical
  candidate is still allowed to fire

## Verification

1. `./mvnw test` — 108 existing plus the new ones, all green.
2. Boot dry-run with a threshold low enough to provoke fires:
   `CF_BOT_STRATEGY_MIN_NET_BPS=1.0 ./mvnw quarkus:dev`
3. Confirm the original bug is gone:
   - `grep '"fired":true' journal/*.ndjson` — fires for one triangle must no longer repeat an
     identical `net_bps` at ~250 ms spacing (that pattern is the bug's signature).
   - `curl -s localhost:8080/q/metrics | grep duplicate_fire` — non-zero during a burst.
   - `curl -s localhost:8080/api/v1/state` — `realizedTradeCount` grows far more slowly than before
     for comparable market activity.
4. No hot-path regression: `/api/v1/latency` `frame_to_decision` p50 should stay ~4–5 µs (it was
   4.4 µs p50 / 39 µs p99 before this change).

## Out of scope

- **"Fix B" — a consumption ledger in `Sizer`** (subtract the depth a paper fill would have taken,
  expiring when the venue republishes the level). The faithful simulation, and the only thing that
  makes dry-run *fill* behaviour realistic. Larger, touches the ladder walk, and needs a
  `cf-arb-bot-plan.md` §5.3 update first.
- **Paper-fill P&L fidelity.** `executePaper` still credits `intent.detectedNetBps()` — no slippage,
  no partial fills — and `Unwinder` is `null` in dry-run so `brokenCycleCount` is structurally always
  0. **This task makes the fire *count* honest; it does not make the *P&L* honest.** Dry-run equity
  must not be read as evidence of profitability.
- Live trading, risk-cap changes, `EdgeCalculator` logic, universe changes, and the `aggre.deals`
  subscription (see decision 3).

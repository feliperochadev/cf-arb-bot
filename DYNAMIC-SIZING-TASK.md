# Task: gross early-out (Phase 1) + dynamic per-tick sizing (Phase 2)

**For:** a follow-up agent, on the current branch (`feat/journal-tuning-p0`).
**Status:** IMPLEMENTED 2026-09-10 on `feat/dynamic-sizing` (branched from `feat/journal-tuning-p0`,
which is not yet merged to `main`) — both phases landed together. See CLAUDE.md's dated entry for
the summary. `DUPLICATE-FIRE-TASK.md` ("Fix A") was already landed and this builds on it
(`Sizer.Result#maxWriteSeq`, `EdgeCalculator.Result#legWriteSeq` exist).
**Evidence:** `JOURNAL-BPS-ANALYSIS.md` §12.1 (U-shaped drag), §14.1–§14.4 (dollar vs bps optimum).

## Objective

Two changes, in order. Phase 1 is free and de-risks Phase 2 by paying for its CPU budget.

1. **Phase 1 — gross early-out.** `net_bps ≤ gross_bps` always, yet `EdgeCalculator` runs the three
   ladder walks unconditionally. Measured over **7.92 M evaluations**, **99.54 %** of those walks
   could not possibly have cleared the gate. Skip them.
2. **Phase 2 — dynamic sizing.** Stop asking `EdgeCalculator` *"is this fixed size good?"* and start
   asking *"what size is best?"* Worth **~2.8× profit per trade** (§14.1).

**Hard constraints:** no change to `cf-bot.dry-run`, any `cf-bot.risk.*` cap, `min-net-bps`, order
type, or the universe. No new thread hand-off (#5). No allocation on the tick path (R1). Fill-in-full
stays (#10) — Phase 2 asks for *less*, never accepts a partial. **Phase 2 touches `EdgeCalculator`,
so non-negotiable #7 applies in full** — see "Protecting the Python cross-check".

---

# Phase 1 — gross early-out

## Why it is provably safe

`drag = gross − net ≥ 0` structurally: the ladder walk starts at top-of-book and only moves to worse
prices, and quantization only floors. Confirmed empirically — **0 of 41,839** journal samples carrying
both values had `net > gross`.

Therefore `gross ≤ gate` ⟹ no size can clear the gate ⟹ pass 2's result cannot change the fire
decision. Skipping it is not a heuristic; it is dead-code elimination at runtime.

Measured over the last four journal files, weighted by `sampled_from`:

```
evaluations represented : 7,924,274
gross_bps > 0           :   129,510  (1.634 %)
gross_bps > 2.0 (gate)  :    36,568  (0.461 %)
```

## The one thing that must not be lost

`JOURNAL-BPS-ANALYSIS.md` §12 depends on paired `(gross_bps, net_bps)` on **reject** lines — that
pair is what yields `drag`, and drag is what produced the U-shape finding and the per-triangle touch
table. A naive early-out makes `net_bps` null on ~99.5 % of rejects and destroys that diagnostic.

**Solution — force a full evaluation once per reject-sample window per triangle.** The journal
already emits only ~1 line/triangle/second, so the diagnostic's *sampling rate is unchanged*; only
the 99.5 % that were never going to be journaled get skipped.

## Implementation

**`strategy/EdgeCalculator.java`**

- Add `public boolean bailedEarly;` to `Result` (reset to `false` at the top of `evaluate`).
- Add an overload:
  `public void evaluate(Triangle, BookRegistry, long startAmount, Result out, double bailBelowGrossBps)`
  The existing 4-arg `evaluate(...)` delegates with `Double.NEGATIVE_INFINITY` — **its behaviour must
  not change** (the Python cross-check calls it).
- After `out.grossBps = ...`, before pass 2:
  ```java
  if (out.grossBps <= bailBelowGrossBps) {
      out.bailedEarly = true;
      return;   // fillable stays false, netBps stays NaN; grossBps + T4 leg arrays are populated
  }
  ```
  The T4 `legTopPriceFixed` / `legTouchQtyFixed` are filled in pass 1, so a bailed candidate still
  carries usable per-leg depth.

**`strategy/OpportunityDetector.java`**

- Add `private final long[] lastFullEvalNanos;` (`[nTri]`, seeded `Long.MIN_VALUE / 2` like the
  existing window arrays).
- In `evaluate(...)`:
  ```java
  boolean wantDiagnostic = nowNanos - lastFullEvalNanos[triangleIndex] >= rejectJournalIntervalNanos;
  double bail = wantDiagnostic ? Double.NEGATIVE_INFINITY : (minNetBps + slippageBufferBps);
  edgeCalculator.evaluate(tri, books, candidateNotional, edgeResult, bail);
  if (wantDiagnostic) lastFullEvalNanos[triangleIndex] = nowNanos;
  ```
- Handle the bailed branch **before** the existing `!fillable` branch — a bailed candidate is not
  "unfillable", it is "provably below the gate". Route it to `journalReject` with a new
  `REASON_GROSS_BAIL` → `"below-gross-ceiling"`, and **do not** increment
  `recordOpportunityRejectedUnfillable()`.
- **T2 window-best ranking** must stay well-defined when `netBps` is `NaN`. Rule, in priority order:
  a fillable candidate beats a bailed one; two fillable rank by `netBps`; two bailed rank by
  `grossBps`; an unfillable ranks below a bailed one only if its `grossBps` is lower. Keep this in
  one small private comparison method with a comment, not inlined.

**Journal compatibility:** `reject_reason` is a free string in `JOURNAL-BPS-ANALYSIS.md` Appendix A's
loader, so the new value is schema-compatible. Note in the doc that analysis scripts computing
`drag = gross − net` must now filter to `reject_reason != "below-gross-ceiling"` (those rows have
`net_bps: null` by construction).

## Phase 1 tests

**`strategy/EdgeCalculatorTest.java`**
- the 4-arg `evaluate` is byte-for-byte unchanged in behaviour (existing tests cover this — they must
  pass untouched)
- with `bailBelowGrossBps` above the computed gross: `bailedEarly == true`, `fillable == false`,
  `netBps` NaN, `grossBps` and the T4 leg arrays still populated
- with it below: identical `Result` to the 4-arg call (assert field-by-field, including `legWriteSeq`)

**`strategy/OpportunityDetectorTest.java`**
- a candidate below the gross ceiling journals `"below-gross-ceiling"`, not `"unfillable"`
- **the diagnostic survives**: drive many sub-gate ticks across two sample windows and assert at least
  one journaled line per window carries a **non-null `net_bps` alongside `gross_bps`**
- a candidate above the gross ceiling is always fully evaluated (never bailed), regardless of window
  state

## Phase 1 verification

- `frame_to_decision` p50 at `/api/v1/latency` should drop materially from its current ~4–5 µs.
- `cfarb_opportunities_detected_total` counts only fully-evaluated candidates, so its rate will fall;
  that is expected, and the new reject reason accounts for the difference. Say so in the commit
  message so it is not read as a feed regression.

---

# Phase 2 — dynamic per-tick sizing

## Why

`OpportunityDetector` currently does:

```java
long candidateNotional = Math.min(eligibleBalance, triangles.maxNotionalFixed(triangleIndex));
edgeCalculator.evaluate(tri, books, candidateNotional, edgeResult);
```

The operator's **risk ceiling** (S6) is being used as the **sizing decision**. Those are different
questions: the cap should *clamp* the optimum, not *be* it.

And the objective is wrong. Profit is `N × net(N)/10⁴` — `N` is in the numerator. Measured on
`usdt-btc-usdc-fwd` (§14.1):

| notional | net bps | $/trade |
|---|---|---|
| $634 (touch) | 0.64 | 0.041 |
| $1,058 | **0.75 ← peak bps** | 0.079 |
| **$2,010** | 0.57 | **0.115 ← peak dollars** |
| $4,000 | −0.10 | −0.041 |

**2.8× by optimising dollars instead of bps.** Only two triangles in the universe have a positive
dollar optimum at all, which is itself a finding worth surfacing per-tick rather than per-analysis.

## Algorithm

`profit(N) = finalAmount(N) − N`. `finalAmount` grows sublinearly in `N` (deeper levels price worse),
so `profit` is concave over the depth dimension and its maximum sits at a **ladder level boundary**.
Enumerate those boundaries, evaluate the full cycle at each, take the argmax.

**`strategy/Sizer.java`** — new pure helper, no state:

```java
/** Cumulative input amounts at each ladder level boundary of the FIRST leg, best-first,
 *  ascending, each clamped to maxInput; plus maxInput itself. Returns the count written. */
public static int candidateInputs(L2Book book, Side side, long maxInput, long[] out);
```

- **ASK** (spending quote): cumulative `Σ mulDiv(askPxAt(i), askQtyAt(i), SCALE)` — a notional.
- **BID** (spending base): cumulative `Σ bidQtyAt(i)` — a quantity.
- Stop at `out.length` (**8**) or when the cumulative exceeds `maxInput`; always include `maxInput`
  as the final candidate; drop duplicates and anything `<= 0`.
- Today every configured triangle's leg 0 is ASK (the USDT anchor is the quote of every leg-0
  symbol), but **handle both** — `Triangle` does not guarantee it and a USDC-anchored cycle would
  break the assumption.

**`strategy/EdgeCalculator.java`** — new entry point, existing one untouched:

```java
public void evaluateBestSize(Triangle triangle, BookRegistry books, long maxStartAmount, Result out);
```

1. Run pass 1 once (gross + T4 leg arrays) — it does not depend on size.
2. Apply the Phase 1 bail against `maxStartAmount`'s gate. `gross` is size-independent, so if it
   fails, **no candidate can clear** — return immediately. This is what makes Phase 2 affordable.
3. `int k = Sizer.candidateInputs(book0, side0, maxStartAmount, candidateScratch);`
4. For each candidate, run the existing pass-2 loop into a scratch `Result`; keep the one maximising
   `finalAmount − startAmount`.
5. Copy the winner into `out`; if none filled, `out.fillable = false`.

Preallocate as fields: `private final long[] candidateScratch = new long[8];` and one reusable
scratch `Result`. **No allocation per evaluation** (R1).

**`strategy/OpportunityDetector.java`**

Replace the single `evaluate(...)` call with `evaluateBestSize(...)`, passing
`min(eligibleBalance, triangles.maxNotionalFixed(triangleIndex))` as `maxStartAmount`. The chosen
notional comes back in `out.legInputAmount[0]` and must be what flows into `OrderIntent` and the
journal's `notional_usd`.

`RiskGates` needs **no change**: `canFire` is already called with the cap-based notional before
sizing, and dynamic sizing can only choose something `≤` that — so the pre-check stays conservative
and `claim()` still happens immediately before dispatch.

## Risk properties (state these in the code comments)

- **Can only size DOWN** from `min(equity, cap)`. The S6 ceiling is untouched; an operator's cap
  still bounds every order.
- **Fill-in-full preserved (#10):** every candidate is evaluated through `Sizer.fillLeg`, which still
  rejects any size the displayed ladder cannot absorb in full. This picks among *fully fillable*
  sizes; it never partial-fills.
- **Fails closed:** no fillable candidate → `fillable = false` → no fire.
- **Bounded work:** at most 8 candidates × 3 legs, and only on the ~0.46 % of ticks that clear the
  gross ceiling.

## Protecting the Python cross-check (non-negotiable #7)

This is the main correctness risk, and the design removes it structurally: **the existing
`evaluate(tri, books, fixedN, out)` is not modified.** `EdgeCalculatorTest`'s cross-check against
`cf-arb-poc/cfarb/stage2_cycles.evaluate_cycle` keeps calling the fixed-size entry point and keeps
asserting the same numbers. `evaluateBestSize` is additive.

Add one **consistency invariant** test tying them together: for the notional `evaluateBestSize`
chose, a direct `evaluate(...)` at that same notional must produce an identical `Result`
(field-by-field). That is what proves the search returns a real evaluation rather than a
differently-computed one.

**`cf-arb-bot-plan.md` §5.3 must be amended before implementation** — it documents `EdgeCalculator`
as evaluating a single given size. Add the sizing-search description there first; the plan is the
spec, not the code.

## Phase 2 tests

**`strategy/SizerTest.java`**
- `candidateInputs` on an ASK book returns ascending cumulative notionals, clamped, deduped
- same on a BID book returns cumulative quantities
- respects the 8-slot bound on a deep book; always includes `maxInput`
- single-level book → exactly one candidate

**`strategy/EdgeCalculatorTest.java`**
- **the motivating case — dollar optimum ≠ bps optimum.** Construct a book where a small size has the
  higher `net_bps` but a larger one yields more absolute profit, and assert `evaluateBestSize` picks
  the larger. *Without this test the change is unverified.*
- never returns a notional above `maxStartAmount`
- falls back to a smaller candidate when the largest is unfillable (e.g. leg 2 below `minNotional`)
- all candidates unfillable → `fillable == false`
- **consistency invariant** (above)
- gross below the gate → returns immediately, `bailedEarly == true`, zero ladder walks (assert via a
  counting `L2Book` subclass or by asserting `legWorstPriceFixed` is all zero)

**`strategy/OpportunityDetectorTest.java`**
- the `OrderIntent` and the journal line both carry the **chosen** notional, not the cap

## Phase 2 journal additions (for measuring the gain in production)

On `opportunity` events add:
- `size_candidates` — how many were evaluated
- `net_bps_at_cap` — what the old fixed-size path would have produced at `maxStartAmount`

The second is the A/B measurement: it makes the 2.8 × claim verifiable from a live capture instead of
from §14.1's two-point model fit. Compute it only on the sampled/journaled line, never on every tick.

## Phase 2 verification

1. `./mvnw test` green, including the untouched Python cross-check.
2. Dry-run: journal `notional_usd` should now **vary** between fires on the same triangle and sit at
   or below `cf-bot.risk.max-notional-usd`. If it is pinned to the cap on every fire, the search is
   not working.
3. Compare `net_bps` vs `net_bps_at_cap` across a capture — the former should be ≥ the latter, and
   `notional_usd × net_bps` should be ≥ `cap × net_bps_at_cap` on essentially every line.
4. `/api/v1/latency` `frame_to_decision` p99 must not regress past its pre-Phase-1 baseline (~39 µs).
   Phase 1's saving should dominate Phase 2's added work; if it does not, lower `MAX_CANDIDATES`.

---

## Suggested sequencing

Phase 1 and Phase 2 are separable — **land Phase 1 first and confirm the latency drop and the drag
diagnostic still appearing in the journal** before starting Phase 2. If Phase 2 is deferred, Phase 1
stands on its own as a pure performance win with no behavioural change.

## Out of scope

- **Lot-step "snap-to-grid" refinement.** §"one real market moment" in the analysis shows `net_bps`
  also has a fine sawtooth from per-leg quantization (amplitude up to ~1.2 bps on `usdt-btc-usdc`,
  far more on coarse-step symbols). Level-boundary candidates do not target it. A follow-up could
  snap each candidate down so the binding leg lands on its lot step. Deferred: the depth dimension is
  worth 10–60 bps, the quantization dimension ~1 bps on the triangles that remain enabled.
- Changing `min-net-bps`. After Phase 2 the gate judges the *best available* size rather than an
  arbitrary one, so raising it back becomes cheap — but that is an operator decision (non-negotiable
  #3), to be taken on a fresh capture, not bundled here.
- Live trading, risk-cap changes, universe changes.

# Journal analysis — where the bps go, and which knobs actually move them

**Data:** `journal/*.ndjson`, 14 files, **191,548 `opportunity` events + 605 `latency_snapshot`
events**, 2026-09-09 17:31 UTC → 2026-09-10 17:45 UTC (≈9.5 h of actual bot uptime inside a 24 h
wall-clock window; there are two gaps, 18:26→00:19 and 04:01→11:11).
**Fires: 0. Cycles executed: 0.** Everything below is dry-run detector output.

**Purpose:** find the tuning changes that would actually move this bot toward a profitable flow,
ranked by the number of basis points each one recovers. This is an evidence document — every number
is reproducible from the journal with the script in the appendix.

---

## TL;DR

1. **The edge exists but it is ~1–4 bps, not ~6+.** Best post-fee top-of-book edge in the entire
   dataset: **+3.98 bps**. The fire gate is `min-net-bps 5.0 + slippage-buffer 1.0` = **6.0 bps**.
   Since `net_bps ≤ gross_bps` in every one of the 41,839 samples where both are recorded, **no
   sizing, latency or execution work could have produced a single fire in this window.** The gate is
   above the ceiling of the opportunity distribution.
2. **The dominant destroyer of edge is order size, not fees and not latency.** Depth-walk +
   quantization drag (`gross_bps − net_bps`) scales ~linearly with notional: median **3.5 bps at
   $1,000 → 8.1 bps at $10,000**, and on the thin cross legs it reaches **20–40 bps**. Of the 120
   moments where a positive post-fee edge existed, **drag exceeded the edge in 120 of 120**.
3. **The second destroyer is one 5 bps leg.** Raw (pre-fee) dislocation p99 is ~+1 to +4 bps across
   every liquid triangle. A single standard `X/USDT` taker leg costs 5 bps — more than the entire
   width of the observed edge distribution. **Only all-zero-fee triangles are arithmetically
   viable**, and only two are configured.
4. **Separately, and more urgently: `BTCUSDT`'s order book latches into an unusable state and never
   recovers.** All five triangles containing it were evaluable **1.6% of the time** in a clean
   sample hour, they go dark together to the second, and in **17 of 17 sessions** they die once and
   stay dead until restart. Confirmed live: `booksReady: false` on exactly those five triangles
   right now. This is a defect, not market structure — and it is silent, because a stale/crossed leg
   is skipped without a log line or a counter. See **§5.5**; it should be fixed before any further
   tuning, since it invalidates the measurement of the best-raw-edge triangle on the venue.

The honest summary: at MEXC's standard 5 bps taker tier, intra-venue triangular arbitrage on this
universe is not profitable at any size. There are three specific changes (§3, §4, §5) that together
move the best triangle from "≈7 bps short of the gate" to "≈1 bps short", and one purchase decision
(MX discount, §4.2) that is worth more than all the code changes combined.

---

## 0. How to read the journal (and what it does not tell you)

Three properties of the data collection shape every conclusion:

| Property | Consequence |
|---|---|
| **Rejects are sampled at 1 Hz per triangle** (`cf-bot.journal.reject-sample-ms=1000`, `OpportunityDetector.journalReject`) and the sample is the **first** reject in each window, not the best | The journal is a ~1 Hz uniform sample of a ~300 evaluations/s stream. Distributions are unbiased; **peaks are under-sampled**. Every "max" below is a floor on the true max. |
| **A triangle with any stale (>250 ms) / untrusted / crossed leg is skipped silently** — `OpportunityDetector.evaluate` returns before journaling | Absence from the journal is data: events/second ≈ the fraction of time the triangle was evaluable at all. See §5. |
| `gross_bps` was only added on 2026-09-10 | `gross_bps` exists for **42,236 samples over 3.14 h** (14:37→17:45 UTC). Everything decomposition-related is drawn from that window. `net_bps` covers all 191,548. |

Three configuration "eras" are present and are time-confounded — read cross-era comparisons as
suggestive, not controlled:

| Era | Window (UTC) | Notional | Triangles | `gross_bps` |
|---|---|---|---|---|
| A | 09-09 17:31 → 09-10 03:00 | $100 | 10 | no |
| B | 09-10 03:00 → 14:52 | $1,000 | 10 → 14 | last 32 s only |
| C | 09-10 14:52 → 17:00 | $10,000 | 14 | yes |
| D | 09-10 17:00 → 17:45 | $10,000 | **24** (post-expansion) | yes |

### The two quantities, precisely

From `EdgeCalculator.evaluate`:

- **`gross_bps`** = `(Π legRate_i · (1 − fee_i)) − 1`, at **top of book**, **fee-inclusive**.
  No depth limit, no lot-size quantization.
- **`net_bps`** = the realized round trip after VWAP-walking each real ladder to size, quantizing to
  the exchange lot step, and applying the same fees.
- Therefore **`drag = gross_bps − net_bps` is purely depth-walk + quantization.** Fees cancel. This
  is the cleanest decomposition available and it is what makes §3 possible.
- And **`raw_bps ≈ gross_bps + Σ taker_bps`** — the pre-fee cyclic dislocation, i.e. what the market
  itself is offering, independent of the fee tier and the size we chose.

---

## 1. The headline: a gate above the ceiling

```
net_bps > 0   :      3 samples of 191,118   (0.0016%)
net_bps > 6.0 :      0 samples              (the actual fire gate)
best net_bps  :  +1.80 bps   usdt-usdc-xrp-rev, $100 notional, 09-10 00:46:16 UTC
best gross_bps:  +3.98 bps   usdt-usdc-xrp-fwd
```

`net_bps ≤ gross_bps` held in **41,839 / 41,839** samples where both were recorded (minimum drag
+0.0012 bps; drag is non-negative by construction — walking a ladder deeper never improves the
price). So `gross_bps` is a hard ceiling on `net_bps`, and the fire rate under **perfect** sizing
and **zero** latency is bounded by `count(gross_bps > T)`:

**Seconds per hour in which a fire was arithmetically possible** (3.14 h window, 42,236 samples):

| gate `T` (bps) | samples | ≈ opportunity-seconds/hour | P&L per fire @ $1k | @ $10k |
|---|---|---|---|---|
| **6.0 — current gate** | **0** | **0.0** | $0.60 | $6.00 |
| 4.0 | 0 | 0.0 | $0.40 | $4.00 |
| 3.0 | 2 | 0.6 | $0.30 | $3.00 |
| 2.0 | 19 | 6.1 | $0.20 | $2.00 |
| 1.0 | 49 | 15.6 | $0.10 | $1.00 |
| 0.5 | 81 | 25.8 | $0.05 | $0.50 |
| 0.0 | 120 | 38.3 | — | — |

Which triangles supply them (`count(gross>0) / >1 / >2 / >3`):

```
usdt-eth-xrp-fwd     103 /  45 /  18 /   1     (n=5,647)
usdt-usdc-xrp-fwd      7 /   2 /   1 /   1     (n=5,236)
usdt-usdc-xrp-rev      7 /   2 /   0 /   0     (n=5,236)
usdt-usdc-tao-rev      3 /   0 /   0 /   0     (n=765)
all other 20 triangles 0
```

**Reading:** the entire tradeable opportunity set on this venue, in this window, is four triangles,
concentrated in one (`usdt-eth-xrp-fwd`, 86% of it). And even that one tops out at +3.07 bps gross.

---

## 2. Where the bps go — the full decomposition

Raw dislocation is what the market offers; fees and drag are what we give back. Per triangle, over
the 3.14 h `gross_bps` window:

| triangle | fee bps | raw p50 | raw p90 | raw p99 | raw max | % raw>0 | drag p50 @$10k | net p99 |
|---|---|---|---|---|---|---|---|---|
| `usdt-usdc-xaut-rev` | 5.0 | −0.29 | −0.18 | −0.13 | −0.12 | 0.0% | **0.52** | −5.27 |
| `usdt-usdc-xaut-fwd` | 5.0 | −0.31 | −0.16 | −0.13 | +0.78 | 0.1% | **0.50** | −5.26 |
| `usdt-btc-usdc-fwd` | 5.0 | −0.77 | **+2.51** | **+3.94** | +3.94 | **43.7%** | 4.43 | −6.92 |
| `usdt-usd1-xaut-rev` | 6.0 | −0.79 | −0.66 | −0.46 | −0.12 | 0.0% | **0.52** | −6.75 |
| `usdt-usdc-usd1-rev` | **0.0** | −1.00 | −0.20 | −0.09 | −0.00 | 0.0% | **1.00** | **−1.20** |
| `usdt-btc-usdc-rev` | 5.0 | −1.10 | +1.08 | +1.92 | +3.07 | 32.0% | 5.54 | −10.16 |
| `usdt-usdc-sol-rev` | 5.0 | −1.19 | +1.40 | +3.40 | +3.80 | 27.6% | 6.75 | −10.48 |
| `usdt-usdc-usd1-fwd` | **0.0** | −1.30 | −0.60 | −0.30 | −0.20 | 0.0% | **1.00** | **−1.30** |
| `usdt-eth-usdc-fwd` | 5.0 | −1.56 | +0.05 | +1.48 | +3.09 | 10.7% | 3.95 | −8.13 |
| `usdt-eth-usdc-rev` | 5.0 | −1.85 | −0.09 | +1.17 | +2.20 | 9.1% | 4.64 | −10.30 |
| `usdt-usdc-sol-fwd` | 5.0 | −2.90 | −0.90 | +2.09 | +4.08 | 9.6% | 28.40 | −29.01 |
| `usdt-usdc-xrp-fwd` | **0.0** | −3.50 | −1.86 | −0.43 | **+3.98** | 0.1% | 11.87 | −13.38 |
| `usdt-usdc-tao-fwd` | 5.0 | −4.37 | +1.02 | +3.16 | +3.93 | 15.2% | 20.05 | −23.03 |
| `usdt-usdc-xrp-rev` | **0.0** | −4.71 | −2.58 | −0.84 | +1.41 | 0.1% | 10.62 | −12.28 |
| `usdt-usdc-tao-rev` | 5.0 | −7.39 | −2.32 | +1.39 | +5.52 | 7.7% | 17.78 | −23.81 |
| `usdt-usd1-xaut-fwd` | 6.0 | −10.76 | −10.52 | −10.27 | −9.44 | 0.0% | 0.70 | −16.76 |
| `usdt-eth-btc-rev` | 15.0 | −14.35 | −0.37 | +4.91 | +5.71 | 9.9% | 8.85 | −30.81 |
| `usdt-eth-xrp-fwd` | 10.0 | −14.99 | **+3.25** | **+10.85** | **+13.07** | **15.1%** | 20.50 | −31.37 |
| `usdt-eth-btc-fwd` | 15.0 | −15.31 | −13.94 | −12.05 | −11.90 | 0.0% | 2.77 | −31.03 |
| `usdt-sol-btc-fwd` | 15.0 | −16.13 | −14.49 | −10.49 | −9.14 | 0.0% | 2.06 | −31.16 |
| `usdt-btc-xrp-fwd` | 10.0 | −16.55 | −13.34 | −11.49 | −11.24 | 0.0% | 62.52 | −28.54 |
| `usdt-eth-xrp-rev` | 10.0 | −18.45 | −16.60 | +0.80 | +7.97 | 1.2% | 7.10 | −29.85 |
| `usdt-btc-xrp-rev` | 10.0 | −19.84 | −18.12 | −16.64 | −16.09 | 0.0% | 8.77 | −32.33 |
| `usdt-sol-btc-rev` | 15.0 | **−69.09** | −30.40 | −3.21 | −2.36 | 0.0% | **147.17** | −226.27 |

Three shapes fall out of this table, and they need different fixes:

- **Tight-and-deep, killed by fees**: `usdt-usdc-xaut`, `usdt-btc-usdc`, `usdt-eth-usdc`,
  `usdt-usdc-usd1`, `usdt-usd1-xaut-rev`. Drag ≤ 5.5 bps, often ≤ 1. `usdt-btc-usdc-fwd` has a
  **positive raw dislocation 43.7% of the time** and p99 +3.94 — and hands all of it to a 5 bps
  `BTCUSDT` leg. → **§4 (fees).**
- **Real edge, killed by depth**: `usdt-eth-xrp-fwd` (raw p99 **+10.85**, max **+13.07**),
  `usdt-usdc-tao-*`, `usdt-usdc-sol-fwd`, `usdt-usdc-xrp-*`. Drag 10–40 bps at $10k. → **§3 (size).**
- **Structurally dead**: `usdt-sol-btc-*`, `usdt-eth-btc-fwd`, `usdt-btc-xrp-*`,
  `usdt-usd1-xaut-fwd`. raw p99 ≤ −10 bps — the venue's own bid/ask spread on `SOLBTC`, `ETHBTC`,
  `XRPBTC` exceeds any plausible dislocation. `usdt-sol-btc-rev`'s raw p50 of **−69 bps** is a
  measurement of how wide the `SOLBTC` ask is, nothing more. → **§6 (prune).**

---

## 3. Lever 1 — order size. Worth 2–40 bps. Biggest single lever.

### 3.1 Drag is ~linear in notional

Median `drag = gross − net`, same triangles, $1,000 vs $10,000:

| triangle | drag @$1k | drag @$10k | ratio |
|---|---|---|---|
| `usdt-eth-usdc-fwd` | 0.39 | 3.95 | **10.1×** |
| `usdt-btc-xrp-fwd` | 6.59 | 62.52 | **9.5×** |
| `usdt-eth-xrp-fwd` | 2.25 | 20.50 | **9.1×** |
| `usdt-eth-usdc-rev` | 0.81 | 4.64 | 5.7× |
| `usdt-btc-usdc-fwd` | 1.12 | 4.43 | 4.0× |
| `usdt-btc-usdc-rev` | 1.46 | 5.54 | 3.8× |
| `usdt-usdc-xrp-rev` | 3.54 | 10.62 | 3.0× |
| `usdt-usdc-xrp-fwd` | 10.83 | 11.87 | 1.1× |
| `usdt-sol-btc-fwd` | 2.07 | 2.06 | 1.0× |

Pooled across all triangles: median drag **3.54 bps @ $1k → 8.10 bps @ $10k**. Pooled median
`net_bps` degrades **−9.87 ($100) → −12.76 ($1k) → −15.53 ($10k)** — roughly 3 bps lost per 10×.

The two sub-linear rows are the informative ones. `usdt-usdc-xrp-fwd` sits at ~11 bps of drag at
**both** $1k and $10k, but at $100 its `net_bps` p50 (−4.34) is already equal to its `gross_bps` p50
at $1k (−4.46) — i.e. **drag ≈ 0 at $100 and ≈ 11 bps at $1,000.** That is a step function, not a
slope: it is the price of stepping off level 1 of the `XRPUSDC` book. **There is a per-symbol "touch
size" — the notional that fits at top of book — and the cost of exceeding it is a fixed jump to
level 2, not a smooth degradation.**

### 3.2 What correct sizing would have been worth

The `usdt-eth-xrp-fwd` episode of 16:50–16:57 UTC is the best opportunity in the dataset: 103
samples with positive post-fee edge, raw up to +13.07 bps, gross up to +3.07 bps. Applying the
measured drag at each notional (drag p50 $10k = 20.50, $1k = 2.25; $100 extrapolated linearly):

| notional | modeled net p50 | modeled net max | samples net>0 | samples net>6 |
|---|---|---|---|---|
| $10,000 (as run) | **−19.57** | −17.43 | 0 / 103 | 0 |
| $1,000 | −1.32 | +0.82 | 5 / 103 | 0 |
| $100 | **+0.71** | **+2.85** | **90 / 103** | 0 |

Sizing alone converts a 103-sample, −19.6 bps loss into a 103-sample, +0.7 bps gain. It does **not**
reach the 6.0 gate — nothing does — but it is the difference between "structurally impossible" and
"marginally positive". *(Caveat: the $1k drag figure rests on 23 samples over a 32-second window;
treat the $100 row as an extrapolation, not a measurement.)*

### 3.3 What to change

The bot currently sizes every cycle identically:
`candidateNotional = min(equity, max-notional-usd)` (`OpportunityDetector.evaluate`), i.e. a flat
**$10,000** with today's `cf-bot.capital.seed-usd=10000` / `cf-bot.risk.max-notional-usd=20000`.
One global cap cannot be right for both `USDCUSDT` (deep) and `XRPETH` (dust).

- **Config-only, do first:** drop `cf-bot.risk.max-notional-usd` to **$500–1,000** for the next
  dry-run capture. This costs nothing and, per §3.1, recovers 5–55 bps of drag on the thin-cross
  triangles immediately.
- **Code, the real fix:** make `Sizer` solve for size instead of accepting it. It already walks the
  ladder — have it walk once and return the notional that maximizes `net_bps` (in practice: the
  level-1-bounded size, subject to `min_notional`), and have `EdgeCalculator` evaluate at that size.
  This is a strictly larger change than a per-triangle cap and should be specified in
  `cf-arb-bot-plan.md` §5.3 before implementation.
- **Config, intermediate:** per-triangle `cf-bot.triangles.<name>.max-notional-usd`, defaulting to
  the global cap. Cheap, and directly expresses "XRPETH gets $200, USDCUSDT gets $10,000".

**Do not** relax `Sizer`'s fill-in-full policy to chase this (CLAUDE.md non-negotiable #10) — the
finding here argues for *asking for less*, not for *accepting partial fills*.

---

## 4. Lever 2 — the fee structure. Worth 2.5–5 bps, and it gates everything.

### 4.1 Only all-zero-fee triangles can clear

Raw dislocation p99 is **+1 to +4 bps** on every liquid triangle in the table. A single standard
`X/USDT` taker leg costs **5.0 bps** — wider than the whole distribution. The arithmetic is
unforgiving: any triangle containing one standard USDT leg is ~1 bp short before depth is even
considered.

MEXC's exact-zero-taker USDT pairs are **`USDCUSDT`, `XRPUSDT`, `USD1USDT`** (and `EURUSDT`);
everything USDC-quoted is 0 bps under the current promotion. Of the 24 configured triangles, exactly
**two** are all-zero-fee: `usdt-usdc-xrp` and `usdt-usdc-usd1`.

`usdt-usdc-usd1` is, on the numbers, **the best triangle on the venue**: `net_bps` p99 −1.20 / −1.30,
drag only **1.00 bps**, fee 0. It is ~1.2 bps from breakeven — closer than anything else by a factor
of four. Its problem is not price, it is availability (§5: 11% duty cycle).

**Candidates worth adding** (from `MEXC-PAIR-EXPANSION.md` §4b, not yet configured):

- `USDT→XRP→USD1→USDT` via **`XRPUSD1`** — 1 bp total (`XRPUSDT` 0 + `XRPUSD1` 1 + `USD1USDT` 0),
  and two of three legs are deep ($44 M / $5.9 M). Best volume profile in the near-zero-fee set.
- `USDT→USDC→EUR→USDT` via **`USDCEUR`/`EURUSDT`** — 0 bps, though it carries intra-cycle EUR FX
  exposure and should be reasoned about before enabling.

### 4.2 The MX discount is worth more than any code change

Holding ≥ 500 MX for 24 h halves taker fees: **5.0 → 2.5 bps** on every standard leg
(`MEXC-PAIR-EXPANSION.md` §2; not modelled in `mexc_filters.json`, which assumes undiscounted 5.0).

Applied to `usdt-btc-usdc-fwd` — raw p90 **+2.51**, p99 **+3.94**, positive **43.7%** of the time,
drag 4.4 bps at $10k and 1.1 bps at $1k:

```
today  (5.0 bps fee, $10k):  net p90 = raw 2.51 − 5.0 − 4.4  = −6.9 bps
+ MX   (2.5 bps fee, $10k):  net p90 = raw 2.51 − 2.5 − 4.4  = −4.4 bps
+ MX   (2.5 bps fee, $1k) :  net p90 = raw 2.51 − 2.5 − 1.1  = −1.1 bps
+ MX   (2.5 bps fee, $1k) :  net p99 = raw 3.94 − 2.5 − 1.1  = +0.3 bps   ← first positive
```

That is the only combination in this entire dataset that puts a deep, high-duty-cycle triangle above
zero. It requires a token purchase and a `taker_bps` refresh in `mexc_filters.json` — no bot code.

**Risk note in the other direction:** the 0.0 bps taker on every USDC/USD1 pair is a promotion with
no announced end date. If it ends, `usdt-usdc-usd1` and `usdt-usdc-xrp` — the only two viable
triangles — become 10–15 bps triangles overnight and silently keep evaluating. A live cutover needs
a fee-refresh mechanism; see the note already recorded in `mexc_filters.json`.

---

## 5. Lever 3 — duty cycle: how often a triangle is *evaluable at all*

This turned out to be the largest single finding in the capture, and it is not a bps finding — it is
a **defect**. §5.1–5.2 establish the measurement, §5.3 splits it into two failure modes, §5.4 is the
tunable one, §5.5 is the defect.

### 5.1 What duty cycle is, and why the journal can measure it

`OpportunityDetector.evaluate` calls `allLegsFresh()` first, and if **any** of the three legs is
untrusted, empty, crossed, or older than `cf-bot.strategy.max-book-age-ms` (250 ms), it returns
**before** anything is journaled or counted. There is no metric for this path — the only trace it
leaves is an event that does not exist.

But the reject stream is sampled at exactly 1 Hz per triangle (`reject-sample-ms=1000`), and every
candidate that clears `allLegsFresh` lands on a reject path. So while a triangle is evaluable it
emits ≈1 event/second, and while it is not it emits nothing:

> **duty cycle ≈ events ÷ elapsed live seconds**, and **the gaps between consecutive events are the
> dark periods themselves.**

The live process confirms the scale of the suppression — `cfarb_journal_suppressed_total`
**2,633,382** vs `cfarb_opportunities_detected_total` **2,713,294**: the journal retains **2.9%** of
evaluations. That is the sampling ratio to keep in mind for every distribution in this document, and
it is exactly what makes the ~1 Hz duty-cycle estimator work.

### 5.2 The method validates cleanly

Forward and reverse of the same loop traverse **identical legs** in opposite directions, so if the
estimator is really measuring leg availability (and not something price-dependent) their event
counts must be equal. Over the 17:00–17:45 window, all 12 loops:

```
usdt-btc-usdc   fwd=  92  rev=  92   delta 0.00%      usdt-usdc-sol   fwd= 963  rev= 963   delta 0.00%
usdt-btc-xrp    fwd=  81  rev=  81   delta 0.00%      usdt-usdc-tao   fwd= 765  rev= 765   delta 0.00%
usdt-eth-btc    fwd=  88  rev=  88   delta 0.00%      usdt-usdc-usd1  fwd= 190  rev= 190   delta 0.00%
usdt-eth-usdc   fwd=2173  rev=2173   delta 0.00%      usdt-usdc-xaut  fwd= 833  rev= 833   delta 0.00%
usdt-eth-xrp    fwd=2205  rev=2205   delta 0.00%      usdt-usdc-xrp   fwd=2018  rev=2018   delta 0.00%
usdt-sol-btc    fwd=  77  rev=  77   delta 0.00%      usdt-usd1-xaut  fwd= 703  rev= 703   delta 0.00%
```

Exact equality in all 12. The estimator measures leg availability and nothing else.

### 5.3 Corrected duty cycle, and two completely different failure modes

The hourly ratios in the first draft of this document were contaminated by **14.66 h of bot
downtime** inside the 24.2 h window (18 outages: a 5.9 h gap, a 7.2 h gap, and 16 restarts —
`quarkus:dev` hot-reloads and reconnects). Subtracting downtime from every inter-event gap:

| triangle (fwd; rev identical) | live h | **duty** | gap p50 | gap p90 | dark periods | median dark | max dark | dark time <2 s / 2–10 s / >10 s |
|---|---|---|---|---|---|---|---|---|
| `usdt-btc-xrp` | 9.31 | **3.2%** | 1.01 s | 1.53 s | 355 | 0.34 s | **12,236 s** | 0.3% / 0.3% / **99.4%** |
| `usdt-btc-usdc` | 9.31 | **3.2%** | 1.01 s | 1.77 s | 316 | 0.44 s | **12,232 s** | 0.3% / 0.3% / **99.4%** |
| `usdt-sol-btc` | 1.64 | **4.6%** | 1.01 s | 1.45 s | 60 | 0.41 s | **4,175 s** | 0.3% / 0.4% / **99.3%** |
| `usdt-eth-btc` | 1.64 | **5.5%** | 1.01 s | 1.12 s | 35 | 0.41 s | **4,167 s** | 0.1% / 0.4% / **99.5%** |
| `usdt-usdc-usd1` | 0.48 | 11.0% | 2.02 s | 14.13 s | 170 | 1.37 s | 558 s | 1.8% / 16.0% / 82.2% |
| `usdt-usd1-xaut` | 0.48 | 40.4% | 1.24 s | 3.02 s | 492 | 0.50 s | 537 s | 11.7% / 34.1% / 54.3% |
| `usdt-usdc-tao` | 0.49 | 43.9% | 1.22 s | 2.60 s | 538 | 0.47 s | 528 s | 14.1% / 30.1% / 55.8% |
| `usdt-usdc-xaut` | 0.49 | 47.9% | 1.24 s | 2.17 s | 566 | 0.50 s | 536 s | 20.3% / 20.9% / 58.8% |
| `usdt-usdc-sol` | 0.49 | 55.4% | 1.04 s | 1.97 s | 441 | 0.46 s | 523 s | 19.8% / 13.2% / 67.1% |
| `usdt-usdc-xrp` | 9.58 | 82.4% | 1.04 s | 1.83 s | 13,276 | 0.28 s | 13 s | 67.3% / 32.2% / 0.4% |
| `usdt-eth-usdc` | 9.58 | 88.8% | 1.01 s | 1.51 s | 8,688 | 0.29 s | 14 s | 79.1% / 19.9% / 1.0% |
| `usdt-eth-xrp` | 9.57 | 89.8% | 1.01 s | 1.37 s | 8,417 | 0.26 s | 31 s | 64.0% / 34.6% / 1.4% |

The last three columns split the failure modes apart:

- **Mode A — slow quoter.** Dark time sits in *short* periods (median 0.26–1.37 s), the longest being
  seconds. The book is fine; it just does not tick often enough to stay inside a 250 ms window.
  **This is what `max-book-age-ms` controls.** → §5.4
- **Mode B — the book latches off.** `usdt-btc-*`, `usdt-eth-btc`, `usdt-sol-btc`: **99.3–99.5% of
  all dark time sits in periods longer than 10 seconds**, with maxima of **1.2 to 3.4 hours**. Note
  their gap p50 is 1.01 s — when these triangles are alive they are *continuously* alive. They do
  not flicker. They work, and then they stop. **No freshness setting fixes this.** → §5.5

*(The `>10 s` share for the five newest triangles is inflated: they only existed for 0.48 h and their
"max dark" of ~530 s is a single restart-adjacent block. Their Mode-A structure is the signal; treat
their long-tail column as unresolved until a clean multi-hour capture exists.)*

### 5.4 Mode A — the tunable part

`cf-bot.strategy.max-book-age-ms=250` is one global number calibrated for BTC-like volatility.
Applied to a stablecoin pegged at 1.0 it is the wrong model: a 2-second-old `USD1USDC` top is almost
certainly still valid; a 2-second-old `BTCUSDT` top certainly is not.

If a leg's quotes arrive with mean interval `T`, the fraction of time its age is ≤ `A` is `A/T` for
strictly periodic arrivals and `1 − e^(−A/T)` for Poisson. Real quote flow sits between the two, so
both bounds are shown. Calibrating `T` from the observed duty at `A` = 250 ms:

| triangle | 250 ms (now) | 500 ms | 1000 ms | 2000 ms | 5000 ms |
|---|---|---|---|---|---|
| | | periodic / Poisson | | | |
| **`usdt-usdc-usd1`** | **11.0%** | 22% / 21% | 44% / 37% | **88% / 61%** | 100% / 90% |
| `usdt-usd1-xaut` | 40.4% | 81% / 64% | 100% / 87% | 100% / 98% | 100% / 100% |
| `usdt-usdc-tao` | 43.9% | 88% / 69% | 100% / 90% | 100% / 99% | 100% / 100% |
| `usdt-usdc-xaut` | 47.9% | 96% / 73% | 100% / 93% | 100% / 99% | 100% / 100% |
| `usdt-usdc-sol` | 55.4% | 100% / 80% | 100% / 96% | 100% / 100% | 100% / 100% |
| `usdt-usdc-xrp` | 82.4% | 100% / 97% | 100% / 100% | 100% / 100% | 100% / 100% |

The independent check: `usdt-usdc-usd1`'s observed gap p50 is **2.02 s**, and its 11% duty at 250 ms
implies `T ≈ 0.25/0.11 ≈ 2.3 s`. Those agree, which is what gives the projection credibility. (The
projection is blind for `T` < 1 s — the 1 Hz sampler floors the observable gap at 1.0 s — which is
why the fast triangles all show gap p50 ≈ 1.01 s regardless.)

**Recommendation:** per-symbol freshness override, e.g.
`cf-bot.symbol-overrides.<SYMBOL>.max-book-age-ms`, raised only for pegged/stable/gold books
(`USD1USDC`, `USD1USDT`, `USDCUSDT`, `GOLD(XAUT)*`, `TAOUSDC`) and left at 250 ms for majors.
For `usdt-usdc-usd1` — the best-priced triangle on the venue — 2000 ms takes it from **11% to
61–88%**, a **5.5–8×** increase in the number of chances to catch its ±1 bp dislocations.

Two constraints on this change:

1. It relaxes a **fail-closed** gate (rule S5), so it must be explicit and per-symbol, never a global
   widening, and it should not exceed the interval over which that symbol's price is genuinely
   stable. A pegged pair earns 2 s; `BTCUSDT` never does.
2. There is a **selection effect I could not rule out with this data**: a thin book only ticks when
   something changes, so the moments right after a wake-up may be systematically different (the tick
   that refreshes the book may be the repricing that removes the edge). Testing this needs per-leg
   book-age on the `opportunity` event, which the journal does not carry today.

**Prerequisite either way:** a stale-skip counter per triangle. Everything in this section had to be
inferred from rows that *were not written*. `cfarb.detector.stale_skip{triangle}`, incremented in the
`allLegsFresh` early return, would make it a direct measurement.

### 5.5 Mode B — `BTCUSDT`'s book latches into an unusable state and never recovers

The four Mode-B triangles have no book in common except `BTCUSDT`. Their other legs (`XRPBTC`,
`BTCUSDC`, `ETHBTC`, `SOLBTC`) are four unrelated markets. If those legs were independently dead,
their alive-windows would be uncorrelated. They are not:

**Jaccard overlap of alive-second sets, clean window 16:00–17:00 UTC (no outage, 14 triangles):**

```
                sol-btc  btc-xrp  btc-usdc  eth-btc │ usdc-xrp  eth-usdc  eth-xrp
  sol-btc         100.0     85.5      87.5     87.5 │      1.6       1.5      1.4
  btc-xrp          85.5    100.0      91.1     91.1 │      1.6       1.6      1.5
  btc-usdc         87.5     91.1     100.0     96.4 │      1.7       1.7      1.6
  eth-btc          87.5     91.1      96.4    100.0 │      1.7       1.7      1.6
  ─────────────────────────────────────────────────┼──────────────────────────────
  usdc-xrp          1.6      1.6       1.7      1.7 │    100.0      88.9     80.9
  eth-usdc          1.5      1.6       1.7      1.7 │     88.9     100.0     83.3
  eth-xrp           1.4      1.5       1.6      1.6 │     80.9      83.3    100.0

  seconds any BTCUSDT-triangle was evaluable:      56 / 3600  ( 1.6%)
  seconds any non-BTCUSDT triangle was evaluable: 3570 / 3600 (99.2%)
```

Two blocks, 85–96% coherent within, 1.4–1.7% across. The four BTCUSDT triangles go dark **together**,
to the second. **`BTCUSDT`'s book was unusable for 98.4% of that hour** — the single most actively
quoted market on the venue.

**Confirmed live on the running process** (`GET /api/v1/triangles`), every triangle containing
`BTCUSDT` is `booksReady: false` *right now*, and every triangle without it is `true`:

```
usdt-btc-usdc-fwd  BTCUSDT:ASK -> BTCUSDC:BID -> USDCUSDT:BID     booksReady: false
usdt-btc-usdc-rev  USDCUSDT:ASK -> BTCUSDC:ASK -> BTCUSDT:BID     booksReady: false
usdt-btc-xrp-fwd   BTCUSDT:ASK -> XRPBTC:ASK -> XRPUSDT:BID       booksReady: false
usdt-sol-btc-fwd   SOLUSDT:ASK -> SOLBTC:BID -> BTCUSDT:BID       booksReady: false
usdt-eth-btc-rev   BTCUSDT:ASK -> ETHBTC:ASK -> ETHUSDT:BID       booksReady: false
usdt-usdc-xrp-fwd  USDCUSDT:ASK -> XRPUSDC:ASK -> XRPUSDT:BID     booksReady: true
usdt-eth-usdc-fwd  ETHUSDT:ASK -> ETHUSDC:BID -> USDCUSDT:BID     booksReady: true
usdt-usdc-usd1-rev USD1USDT:ASK -> USD1USDC:BID -> USDCUSDT:BID   booksReady: true
   ... (all 24: the 5 false are exactly the 5 containing BTCUSDT)
```

#### Narrowing the cause

`booksReady` is `isTrusted() && !isEmpty() && !isCrossed()` — it **does not include the age check**.
So this is *not* a staleness problem and `max-book-age-ms` is irrelevant to it. Of the three:

- **`isTrusted()` — ruled out.** The activity report counts `isTrusted()` books and prints
  `books 30/30 warm`. All 30 books, `BTCUSDT` included, are trusted.
- **`isEmpty()` (`bidCount == 0 || askCount == 0`) — implausible.** `BTCUSDT` is receiving frames
  continuously (`cfarb_frames_received_total` 3,212,638 across the universe).
- **`isCrossed()` (`bidPx[0] >= askPx[0]`) — the remaining candidate.**

The time structure fits `isCrossed()` exactly. **`isCrossed()` has no self-healing path**: nothing in
`L2Book.apply()` removes crossing levels, so once the top bid is ≥ the top ask the book is
permanently unusable until `reset()`, which only fires on a version-chain gap
(`f.fromVersion > lastToVersion + 1`). That predicts a one-way latch — works, then stops, never
recovers mid-session. That is precisely what every session shows:

```
session start (UTC)   session length   BTCUSDT triangles alive until   survived
09-09 17:32:37             53.7 min                       17:35:54       197 s
09-10 00:40:36            180.7 min                       00:42:24       108 s
09-10 03:43:09             18.1 min                       03:50:56       467 s
09-10 11:11:44            211.0 min                       14:37:49    12,365 s
09-10 14:49:01              3.3 min                       14:52:18       197 s
09-10 15:16:07              2.6 min                       15:17:50       103 s
09-10 15:58:15             82.7 min                       17:11:32     4,397 s
09-10 17:26:44             18.7 min                       17:29:33       169 s
   ... 17 sessions, survival 0 s – 12,365 s (median ≈ 110 s)
```

**In 17 of 17 sessions the BTCUSDT triangles die once and stay dead for the rest of the session.**
Never a mid-session recovery. Survival time varies from 10 s to 3.4 h, i.e. the trigger is a market
event rather than a timer — consistent with "a price move large enough to outrun the retained
ladder".

#### Why `BTCUSDT` and not the others

`L2Book` keeps a bounded ladder: `CAPACITY 512`, pruned to `PRUNE_KEEP 200` best levels per side
whenever a side exceeds `PRUNE_TRIGGER 400`. At `BTCUSDT`'s 0.01 tick, **200 levels spans about
$2.00** — roughly **1.7 bps** of a $120k price, a range this book crosses routinely. If MEXC's
`aggre.depth@10ms` stream does not deliver an explicit `qty=0` for every level that leaves the touch
during a fast move (or delivers only a window of levels around the touch), stale bids on the far side
of the move survive at index 0 and the book crosses. Every other symbol in the universe has a much
larger relative tick, so its 200-level window covers a far wider relative price range.

This directly contradicts `L2Book`'s own javadoc invariant — *"deletions arrive explicitly as qty=0,
so it never contains a stale level … the reconstructed spread is always >= the true spread"*. That
invariant was inherited from `cf-arb-poc/cfarb/book.py`, which was validated against a different
channel; `cf-bot.venue.depth-channel` was later switched to `aggre.depth@10ms` by Gate 0 step 4. The
invariant appears not to hold for that channel on a fine-ticked book.

**Status: root cause is a strong hypothesis, not confirmed.** The evidence chain (coincidence test →
live API → `isTrusted` eliminated → `isEmpty` implausible → one-way latch consistent with no
self-heal) is tight, but the bot does not expose per-book state, so which predicate actually fails
has not been observed directly.

#### What to do, in order

1. **Expose per-symbol book state on `/api/v1/state`** — `trusted`, `crossed`, `empty`, `ageMs`,
   `updateCount`, `bidCount`/`askCount`, top bid/ask. This is already an open item from plan §8
   ("per-symbol book age") and it converts the hypothesis into an observation in minutes.
2. **Add a crossed-book counter + WARN**, and treat a book that stays crossed beyond a short grace
   period as a health event: `reset()` it (forcing a clean re-sync) rather than letting it latch.
   A crossed book is currently a silent, permanent, per-symbol outage.
3. **Replay a captured `BTCUSDT` frame sequence through `L2Book`** and assert the book never crosses
   — the existing fixtures (`src/test/resources/fixtures/mexc_depth_*.pb.bin`) plus a longer capture
   from `recorder-service/data/mexc/` make this an offline unit test, no live venue (rule Q10).
4. Only then decide the real fix: a wider retained ladder, pruning by price distance rather than
   level count, or periodic re-sync.

**Impact on this analysis:** every conclusion about the BTC-family triangles in §2 and §6 rests on
1.6–5.5% of the intended sample. `usdt-btc-usdc-fwd` — raw dislocation positive **43.7%** of the
time, p99 **+3.94 bps**, the single best raw-edge profile among the deep triangles, and the one
triangle the MX discount could plausibly push positive (§4.2) — has been effectively **unobserved**.
It should not be pruned as "dead" (§6); it has never really been measured.

---

## 6. Lever 4 — prune the dead triangles. Worth CPU and journal budget, not bps.

Eight of the 24 configured triangles have raw p99 ≤ −10 bps — their legs' own bid/ask spread exceeds
any dislocation that has ever appeared — **and** duty cycles under 8%:

```
usdt-sol-btc-fwd   raw p99 −10.49    usdt-sol-btc-rev   raw p50 −69.09 (!)  drag p50 147 bps
usdt-eth-btc-fwd   raw p99 −12.05    usdt-btc-xrp-fwd   raw p99 −11.49      drag p50 62.5 bps
usdt-btc-xrp-rev   raw p99 −16.64    usdt-usd1-xaut-fwd raw p99 −10.27
```

`usdt-sol-btc-rev` is the clearest case: 15 bps of fee on top of a `SOLBTC` book so wide that the
top-of-book round trip alone costs 69 bps, and a $10k order walks 147 bps into it. It cannot fire at
any threshold, any size, any latency. These were added on the strength of `cf-arb-poc`'s August
capture (`usdt-sol-btc-fwd` was its #1 cycle by realized PnL) — that regime is not present in this
data, and they should be disabled until it returns rather than left evaluating.

Disabling them frees Netty event-loop budget and journal quota for the triangles that matter. Keep
`usdt-eth-xrp-fwd` — despite raw p50 −15, it produced 86% of all positive-edge moments in the
dataset (its distribution is extremely fat-tailed: p50 −14.99, p90 +3.25, max +13.07).

> **Caveat added after §5.5 — do not prune the BTC family on this evidence.** Every triangle in the
> list above except `usdt-usd1-xaut-fwd` contains `BTCUSDT`, and was therefore evaluable only
> 1.6–5.5% of the time. Their raw-dislocation statistics come from a handful of seconds immediately
> after each restart, before the book latched. `usdt-btc-usdc` in particular has the **best** raw
> profile of any deep triangle (positive 43.7% of the time, p99 +3.94 bps) and must not be discarded
> as dead. **Fix the book first (§5.5), re-capture, then decide what to prune.** The only entries
> here that are safely prunable today are `usdt-usd1-xaut-fwd` (40% duty, raw p99 −10.27, measured
> honestly) and — on the strength of its `SOLBTC` spread rather than its duty cycle —
> `usdt-sol-btc-rev`.

---

## 7. What is *not* the problem

**Latency.** `frame_to_decision` p50 **4.4 µs**, p99 **39 µs** (median across 605 snapshots; p99
never exceeded 96.8 µs). The single 1.37 s `max_us` is a cumulative-histogram lifetime value that
never resets — a JIT/GC warmup artifact from one startup, not a recurring event.

**Opportunity lifetime.** Positive-`gross_bps` episodes (consecutive 1 Hz samples on one triangle):

```
28 episodes over 3.14 h  =  8.9 episodes/hour
usdt-eth-xrp-fwd:  12 episodes, median 3.5 samples, longest 41 samples / 48.3 seconds
usdt-usdc-xrp-*:  13 episodes, median 1 sample
```

The best opportunity in the dataset **persisted for 48 seconds**. Against a ~230 ms three-leg REST
round trip, that is ~200× more time than needed. **This bot is not losing races; it is refusing
trades that are genuinely unprofitable at the size it asks for.**

The corollary matters for roadmap decisions: the zero-copy decode work and the Rust migration
(`RUST-MIGRATION.md`) buy nothing for profitability at this stage. They are correctness/cost items,
not edge items.

---

## 8. Threshold tuning — what `min-net-bps` actually buys

`% of journaled samples clearing a given net_bps gate`:

| | n | >6.0 | >3 | >1 | >0 | −1 | −2 | −3 | −5 |
|---|---|---|---|---|---|---|---|---|---|
| all | 191,118 | **0%** | 0% | 0.0010% | 0.0016% | 0.024% | 0.363% | 1.235% | 7.05% |
| $100 | 72,037 | 0% | 0% | 0.0014% | 0.0028% | 0.056% | 0.637% | 2.601% | 16.51% |
| $1k | 77,641 | 0% | 0% | 0.0013% | 0.0013% | 0.006% | 0.059% | 0.142% | 1.55% |
| $10k | 41,440 | 0% | 0% | 0% | 0% | 0% | 0.454% | 0.907% | 0.92% |

Two conclusions:

1. **Lowering `min-net-bps` alone accomplishes nothing.** Between 6.0 and 1.0 the count goes 0 → 2.
   The distribution has no mass there. Threshold is not the binding constraint; size and fees are.
   Any threshold change should follow §3 and §4, not precede them.
2. **`slippage-buffer-bps=1.0` may be double-counting.** `net_bps` is already computed at the
   *worst ladder price the VWAP walk touched*, and `CycleExecutor` submits each IOC at exactly that
   boundary price (`Sizer.Result#worstPriceFixed`). The buffer is a second haircut on an already
   worst-case number. It is 1 bp — against an edge distribution whose p99 is +3.94, that is 25% of
   the available signal. It should be re-derived from measured leg-failure cost once real fills
   exist, not carried as a round number.

The correct sequence is: fix size (§3) → fix fees (§4) → fix duty cycle (§5) → *then* re-measure the
distribution and set `min-net-bps` from it.

### 8.1 Scenario test: MX fee discount + `min-net-bps 3.0` (gate 4.0)

A concrete proposal, evaluated against the capture. Modelling a taker discount `d` as
`net_new ≈ net_old + d × Σfee` (first-order; the exact second-order term is <1e-5 bps here):

| scenario | n | max `net_bps` | samples > gate | samples > 0 |
|---|---|---|---|---|
| today, gate 6.0 | 191,118 | +1.80 | **0** | 3 |
| today, gate 4.0 | 191,118 | +1.80 | **0** | 3 |
| **MX −20%, gate 4.0** | 191,118 | **+1.80** | **0** | 4 |
| MX −50%, gate 4.0 | 191,118 | +2.10 | **0** | 20 |

**Answer: no — not on its own.** Three reasons, in order of importance:

1. **The discount does not touch the triangles that are closest to profitable.** The best sample in
   the dataset (+1.80 bps) is `usdt-usdc-xrp-rev`, which pays **zero** fees — an MX discount is worth
   exactly **+0.00 bps** to it, and to `usdt-usdc-usd1`. Per-triangle benefit at −20%: `+0.00` for
   the two zero-fee loops, `+1.00` for the 5 bps loops, `+2.00` for the 10 bps, `+3.00` for the 15 bps.
   The discount helps most exactly where the *market* edge is worst.
2. **A 4.0 gate is above the all-time raw dislocation of 23 of 24 triangles.** A necessary condition
   to ever clear it is `raw_max > 4.0 + fee×(1−d)`, ignoring drag entirely. At −20%:

   | triangle | fee | raw max | needs | can clear? |
   |---|---|---|---|---|
   | `usdt-eth-xrp-fwd` | 10.0 | **+13.07** | 12.0 | **yes** |
   | `usdt-eth-xrp-rev` | 10.0 | +7.97 | 12.0 | no |
   | `usdt-eth-btc-rev` | 15.0 | +5.71 | 16.0 | no |
   | `usdt-usdc-tao-rev` | 5.0 | +5.52 | 8.0 | no |
   | `usdt-usdc-sol-fwd` | 5.0 | +4.08 | 8.0 | no |
   | `usdt-usdc-xrp-fwd` | **0.0** | +3.98 | 4.0 | no (by 0.02 bps) |
   | `usdt-btc-usdc-fwd` | 5.0 | +3.94 | 8.0 | no |
   | *…17 more* | | ≤ +3.93 | | no |

   **1 of 24** could clear a 4.0 gate at −20% *even with perfect sizing and zero drag.*
3. **The one that can, can't be sized into.** `usdt-eth-xrp-fwd`'s edge lives on `XRPETH`, a
   ~$0.00 M/24 h book. Its drag is 20.5 bps at $10k and 2.25 bps at $1k, so with MX −20%:

   ```
   18 qualifying ticks, mean gross_mx20 = 4.32 bps, max 5.07  (5 episodes = 1.6 fires/h)
     @ $10,000 :  4.32 − 20.50 = −16.2 bps  ->  0 fires
     @ $ 1,000 :  4.32 −  2.25 =  +2.07 bps ->  0 fires (below the 4.0 gate)
     @ $   100 :  4.32 −  0.22 =  +4.10 bps ->  fires, barely
   ```

**So the combination that actually fires is MX −20% + gate 4.0 + notional cut to ~$100** — and it
yields **1.6 fires/hour × $100 × ~4.1 bps ≈ $0.04 per fire ≈ $0.07/hour**, from a single triangle,
on the thinnest book in the universe, before any leg-failure loss. At −50% it becomes 4.1 fires/h at
~5.3 bps ≈ $0.22/hour, still $100-sized and still one triangle.

**Two caveats that cut in opposite directions.** The journal keeps only 2.9% of evaluations, so the
true peak is higher than observed — an exponential fit to `usdt-eth-xrp-fwd`'s tail extrapolates a
full-stream max near **+8.6 bps** gross under MX −20%, i.e. real fires probably exist that this
sample missed. Against that: `usdt-btc-usdc` — the one triangle whose *raw* profile is genuinely
attractive (positive 43.7% of the time) — was unobservable for 97% of the capture (§5.5), so its
true distribution is unknown and could be better or worse than the 3% we saw.

**Conclusion:** the discount and the threshold are both real improvements and both worth taking, but
neither addresses the binding constraint. **Size does.** Fees and thresholds shift the distribution
by a constant ~1–3 bps; drag varies from 0.2 to 62 bps with the size you ask for. Fix §5.5, then
§3, then re-measure — and note that the MX discount tier itself needs verifying
(`MEXC-PAIR-EXPANSION.md` §2 states **50%**, not 20%; the two give materially different answers).

### 8.2 Full stack: P0 book fix + `max-book-age` 2000 ms + $100 notional + MX −20% + gate 4.0

The natural follow-up: apply *everything*, including restoring the triangles the §5.5 defect hid.
This required extrapolating past the observed data, so the method is stated before the answer.

**Method.** For each triangle, fit an exponential tail (peaks-over-threshold, `u` = p95) to the
observed `raw_bps` — the pre-fee dislocation, which is a property of the *market*, independent of our
fee tier and our size. Then ask what the maximum would look like over `N×` more samples, where
`N = (duty_fixed / duty_observed) × (1 / 0.029)`: the first factor restores what §5.5 and the
freshness gate hid, the second undoes the journal's 2.9% retention. A triangle can fire only if
`raw > 4.0 + fee×0.8`, and that ignores drag entirely — it is a ceiling, not a forecast.

| triangle | fee | duty now → fixed | N× | raw max obs | **est. raw max** | needs | |
|---|---|---|---|---|---|---|---|
| `usdt-eth-xrp-fwd` | 10.0 | 90% → 100% | 38× | +13.07 | **+25.09** | 12.0 | **possible** |
| `usdt-usdc-tao-fwd` | 5.0 | 44% → 99% | 78× | +3.93 | **+8.69** | 8.0 | **possible** |
| `usdt-usdc-sol-fwd` | 5.0 | 55% → 100% | 62× | +4.08 | **+8.20** | 8.0 | **possible** |
| `usdt-usdc-xrp-rev` | 0.0 | 82% → 100% | 42× | +1.41 | **+4.13** | 4.0 | **possible** |
| `usdt-btc-usdc-rev` | 5.0 | **3.2% → 90%** | **970×** | +3.07 | +6.35 | 8.0 | no |
| `usdt-eth-btc-rev` | 15.0 | **5.5% → 90%** | **569×** | +5.71 | +12.23 | 16.0 | no |
| `usdt-eth-usdc-fwd` | 5.0 | 89% → 100% | 39× | +3.09 | +5.73 | 8.0 | no |
| `usdt-usdc-xaut-*` | 5.0 | 48% → 99% | 71× | +0.78 | +0.28 | 8.0 | no |
| `usdt-btc-xrp-rev` | 10.0 | 3.2% → 90% | 979× | −16.09 | −11.39 | 12.0 | no |
| *…9 more* | | | | | | | no |

**So the answer to "is there a real chance of profit" is: a narrow, heavily-caveated maybe — and the
strongest single reason to doubt it is what the extrapolation is built on.**

Feeding the fitted tails back through exceedance rates and observed episode clustering:

| triangle | P(raw > need) | fires/h | mean net | $/fire @$100 | **$/day @$100** |
|---|---|---|---|---|---|
| `usdt-eth-xrp-fwd` | 4.8e−03 | 1.85 | +5.89 bps | $0.059 | **$2.61** |
| `usdt-usdc-tao-fwd` | 3.6e−05 | 0.01 | +4.89 bps | $0.049 | $0.013 |
| `usdt-usdc-xrp-rev` | 5.6e−06 | 0.01 | +4.65 bps | $0.047 | $0.006 |
| `usdt-usdc-sol-fwd` | 2.0e−05 | 0.00 | +5.01 bps | $0.050 | $0.005 |
| | | | | **total** | **$2.63/day** |

**$2.63/day at $100 notional — and 99.2% of it is one triangle.** Which makes that triangle the
whole question.

#### Why `usdt-eth-xrp-fwd`'s contribution should not be believed

**1. It is one hour out of twenty-four.** `raw ≥ net + fee` always (drag ≥ 0), so `net + fee` is a
hard lower bound on raw in the eras before `gross_bps` existed. Per hour, across 30,802 samples:

```
09-09 17h  -16.04   09-10 01h   -4.42   09-10 04h  -18.18   09-10 13h  -16.49
09-09 18h  -17.97   09-10 02h   -1.28   09-10 11h  -16.59   09-10 14h  -16.25
09-10 00h  -16.80   09-10 03h   -2.15   09-10 12h  -15.51   09-10 15h  -17.20
                                                            09-10 16h  +13.07  <-- the entire result
                                                            09-10 17h   +5.39
```

Every other hour in the capture sits at or below ~0. The tail fit is dominated by a single episode,
and an exponential tail fitted to one excursion is an extrapolation of that excursion, not of the
market.

**2. The shape is wrong for an arbitrage.** Minute by minute through the episode, `raw` jumps from
−10 to +8 between 16:44 and 16:45, then holds a **median** of +6.8 bps (peaks +13.07) for **13
minutes** before collapsing. A genuine tradeable dislocation does not survive 13 minutes at a +7 bps
median on any venue with participants — it gets taken. A sustained, un-taken dislocation on a
**~$0.00 M/24 h** book (`XRPETH`, `MEXC-PAIR-EXPANSION.md` §3) is the signature of a quote that is
**displayed but not fillable in size**.

**3. The bot's own depth walk says exactly that.** Over the 701 samples of the episode:

```
raw   p50  +6.81   (top-of-book says: money on the table)
drag  p50  36.14   (the ladder says: not at $10,000 it isn't)
net   p50 −40.02   (the bot refused, every single tick — correctly)
```

**4. And the size that *would* have worked is unknowable from this data.** `drag($100)` can only be
extrapolated linearly, and the one triangle where drag was measured at two sizes
(`usdt-usdc-xrp`: ~11 bps at **both** $1k and $10k, ~0 at $100) shows a **step function at the touch
size, not a slope** — so linear extrapolation is known to be the wrong model. The journal carries no
per-leg touch quantity (§9 gap 2 / task T4), so the safe notional cannot be recovered offline.

#### The direct evidence points the other way

The $100 era is not a model — it happened. **72,038 samples, ~5.9 h, 10 triangles, at exactly the
notional being proposed:**

```
max net_bps as run       : +1.80
max net_bps with MX −20% : +1.80   (the best sample is usdt-usdc-xrp-rev, fee 0 — the discount adds nothing)
samples clearing gate 4.0: 0
best non-zero-fee triangle at $100, with MX −20%: usdt-btc-usdc-fwd at −3.10
```

Zero fires, and the gap to a 4.0 gate is ~2.2 bps on the best sample and ~7 bps on the best
fee-paying one.

#### And `max-book-age` 2000 ms cuts against this specific trade

Raising the freshness window is well-justified for **pegged** books (§5.4: `USD1USDC` quotes every
~2.3 s and its price genuinely does not move). It is the *opposite* of what you want on `XRPETH`,
where the apparent edge is most likely a stale quote in the first place. A 2 s window admits more of
exactly that. **§5.4's recommendation was per-symbol for this reason — the analysis supports 2000 ms
for `USD1USDC`/`USD1USDT`/`USDCUSDT`/`GOLD(XAUT)*`, and specifically not for a thin cross leg whose
edge signature is indistinguishable from a stale quote.** Note also that the freshness gate was
*not* the binding constraint on the one triangle that drives the $2.63: `usdt-eth-xrp` already ran at
90% duty, so 2000 ms adds ~11% more samples to it, not 8×.

#### Verdict

| | |
|---|---|
| Ceiling if the extrapolation is right | **~$2.63/day at $100** (~$0.06/fire, ~1.9 fires/h) |
| Share from one thin-book triangle | **99.2%** |
| That triangle's edge, cross-checked | 13 min of one hour; sustained +6.8 bps median un-taken; drag 36 bps |
| Direct evidence at $100 (72k samples, 5.9 h) | **0 fires, best +1.80 bps vs a 4.0 gate** |
| Not modelled | leg-failure loss, adverse selection, `quoteOrderQty` unverified, stale-quote fill risk |

**The stack does not make the bot profitable.** It moves it from "arithmetically impossible" (§8.1:
1 of 24 triangles could clear the gate, and couldn't be sized into) to "a handful of triangles could
occasionally clear a 4 bps gate, at ~$0.05 a fire, concentrated in a book whose edge is probably not
executable." That is not a profitable flow; it is a measurable one — which is genuinely worth having,
because it is the state in which the next question can actually be answered.

**What would change this verdict, in order:** (1) T4's per-leg touch depth, which converts every
"drag at $100" extrapolation here into a measurement and settles whether `XRPETH`'s edge is fillable
at *any* size; (2) T1's book fix plus a clean multi-hour capture with no restarts, which is the only
way to find out what `usdt-btc-usdc` — 43.7% positive raw, best profile of any deep triangle, and
observed on 3% of its sample — actually does; (3) confirming the real MX tier, since 50% rather than
20% changes the `need` column by 2.5 bps on every fee-paying triangle and turns several "no" rows
into "possible". Until (1) and (2) exist, any expected-value number here — including the $2.63 — is a
fit to a single 13-minute excursion on the thinnest book in the universe.

---

## 9. Telemetry gaps blocking the next iteration

These are the things this analysis wanted and could not get:

1. **Reject sampling keeps the first event per second, not the best.** `OpportunityDetector.journalReject`
   should track a per-triangle running max of `net_bps`/`gross_bps` within the window and emit
   *that*. Same line count, same allocation budget, but the tail — the only part that matters —
   stops being invisible. **Highest-value change in this list.**
2. **No per-leg depth in the journal.** The single most important open question — "what notional
   maximizes `net_bps` on this book right now?" — cannot be answered offline because the events
   carry no touch size. `EdgeCalculator.Result` already has `legWorstPriceFixed[]`; adding
   top-of-book price and available touch quantity per leg to the `opportunity` event would make §3
   a measurement instead of an extrapolation.
3. **Stale-leg skips are silent, and no per-symbol book state is exposed anywhere.** Duty cycle had
   to be inferred from rows that were *not* written (§5), and §5.5's root cause could be narrowed but
   not confirmed because nothing reports whether a given book is crossed, empty, or how old it is.
   Two additions close this: a `cfarb.detector.stale_skip{triangle}` counter in the `allLegsFresh`
   early return, and per-symbol `trusted`/`crossed`/`empty`/`ageMs`/`updateCount` on
   `/api/v1/state` (already an open item in plan §8). **A crossed book is currently a permanent,
   silent, per-symbol outage with no counter, no WARN, and no self-heal.**
4. **No per-stage latency breakdown** — `decisionToLeg1AckNanos` blends place→reconcile→commission
   into one histogram (already a documented gap in CLAUDE.md). Not blocking today, since latency is
   not the constraint.
5. **`notional_usd` changed mid-capture with no run marker.** Era boundaries had to be reverse-
   engineered from the data. A `config_snapshot` event at startup (seed, caps, threshold, symbol
   count, triangle count, fee table hash) would make every future capture self-describing.

---

## 10. Ranked recommendations

| # | Change | Kind | bps recovered | Confidence |
|---|---|---|---|---|
| **0** | **Fix the `BTCUSDT` book latch (§5.5)** — expose per-symbol book state, add a crossed-book counter + WARN, `reset()` a book that stays crossed, regression-test `L2Book` against a captured BTCUSDT sequence | **defect** | restores **5 of 24 triangles from 1.6% to ~100% observability**, including the best raw-edge one | High that it's broken; Medium on the exact cause |
| 1 | Cut `cf-bot.risk.max-notional-usd` to **$500–1,000** for the next capture | config | **5–55** on thin-cross triangles | High — §3.1 measured |
| 2 | Journal the **max** reject per window, not the first | code (small) | 0 (unblocks all future tuning) | High |
| 3 | Acquire **≥500 MX** for the 50% taker discount; refresh `taker_bps` in `mexc_filters.json` | purchase + config | **2.5 per standard leg** | High — §4.2 |
| 4 | Per-symbol `max-book-age-ms`; long for pegged/stable/gold pairs only | config + small code | `usdt-usdc-usd1` **11% → 61–88%** duty (5.5–8×) | Medium — §5.4 |
| 5 | Add `cfarb.detector.stale_skip{triangle}` counter | code (small) | 0 (makes §5 a measurement, not an inference) | High |
| 6 | Log per-leg touch depth on `opportunity` events | code (small) | 0 (unblocks #8) | High |
| 7 | Disable `usdt-sol-btc-rev` and `usdt-usd1-xaut-fwd`; **hold** on the rest of the BTC family until #0 lands | config | 0 bps; frees CPU + journal | High — §6 |
| 8 | `Sizer` solves for the net-maximizing notional instead of accepting one | code (large, needs plan §5.3 update) | **2–40**, supersedes #1 | Medium |
| 9 | Add `XRPUSD1` → `usdt-xrp-usd1` (1 bp total, two deep legs) | config + filters | new 1-bp triangle | Medium — §4.1 |
| 10 | Add a `config_snapshot` startup event | code (small) | 0 (capture hygiene) | High |
| 11 | Re-derive `slippage-buffer-bps` from measured leg-failure cost; do **not** touch `min-net-bps` until #0/#1/#3/#4 land | analysis | up to 1 | Medium — §8 |

### The uncomfortable conclusion

Stack every lever above at their measured values and the best case is roughly:
`usdt-btc-usdc-fwd`, raw p99 +3.94 − MX-discounted fee 2.5 − drag at $1k 1.1 = **+0.3 bps** (on a
triangle that should be available most of the time once §5.5 is fixed — today it is 3%); or
`usdt-usdc-usd1` at ~0 bps, available 61–88% of the time after a freshness fix. At $1,000 notional,
+0.3 bps is **$0.03 per cycle**, before any leg-failure loss.

That is not a business. It is, however, exactly what `MEXC-PAIR-EXPANSION.md` §3 predicted and what
this capture was built to test — and the bot's refusal to fire is the `EdgeCalculator` arithmetic
working correctly (non-negotiable #7), not a tuning failure. The levers above are worth doing
because they are how you'd find out if a *different* regime (a volatile session, a delisting, a new
listing's first hours) ever opens a real window. The strategic answer remains
`MEXC-PAIR-EXPANSION.md` §3's: **cross-venue or spot-perp basis, not intra-MEXC triangular.**

---

## Appendix A — reproduction

```python
import json, glob, pandas as pd, numpy as np
rows = []
for f in sorted(glob.glob("journal/*.ndjson")):
    for ln in open(f):
        d = json.loads(ln)
        if d["type"] != "opportunity": continue
        rows.append((d["ts_us"], d["triangle"], d["net_bps"], d.get("gross_bps"),
                     d["notional_usd"], d["fired"], d["reject_reason"]))
df = pd.DataFrame(rows, columns=["ts_us","tri","net","gross","notional","fired","reason"])
df["dt"] = pd.to_datetime(df.ts_us, unit="us", utc=True)
df["drag"] = df.gross - df.net          # depth walk + quantization only (fees cancel)
# per-triangle round-trip taker fee, parsed from application.properties + mexc_filters.json:
df["raw"] = df.gross + df.tri.map(FEES) # pre-fee cyclic dislocation
```

Duty cycle: `events_in_hour / elapsed_seconds_in_hour` — valid because rejects are sampled at
exactly 1 Hz per triangle and stale triangles emit nothing.

## Appendix B — data inventory

| file | events | span (UTC) | notional | triangles |
|---|---|---|---|---|
| `20260909-17` | 8,976 | 17:31:16 → 17:59:59 | $100 | 10 |
| `20260909-18` | 8,208 | 18:00:00 → 18:26:16 | $100 | 6 |
| `20260910-00` | 6,094 | 00:19:06 → 00:59:59 | $100 | 10 |
| `20260910-01` | 18,422 | 01:00:00 → 01:59:59 | $100 | 6 |
| `20260910-02` | 17,820 | 02:00:00 → 02:59:59 | $100 | 6 |
| `20260910-03` | 18,816 | 03:00:00 → 03:59:59 | $100 → $1,000 | 10 |
| `20260910-04` | 390 | 04:00:00 → 04:01:16 | $1,000 | 6 |
| `20260910-11` | 13,938 | 11:11:44 → 11:59:59 | $1,000 | 10 |
| `20260910-12` | 19,738 | 12:00:00 → 12:59:59 | $1,000 | 6 |
| `20260910-13` | 20,294 | 13:00:00 → 13:59:59 | $1,000 | 6 |
| `20260910-14` | 15,938 | 14:00:00 → 14:52:33 | $1,000 | 14 |
| `20260910-15` | 2,916 | 14:52:33 → 15:59:59 | $1,000 → $10,000 | 14 |
| `20260910-16` | 19,622 | 16:00:00 → 16:59:59 | $10,000 | 14 |
| `20260910-17` | 19,570 | 17:00:00 → 17:45:27 | $10,000 | **24** |

*(A dry-run instance was still running and appending during this analysis, so the last file grew
between passes; per-file counts here are from the inventory pass, the 191,548 headline from the
load pass. The difference is ~800 events in `20260910-17` and changes no conclusion.)*

`unfillable` rejects (Sizer could not fill the full size off displayed depth): 430 total — 1 at
$100, 33 at $1,000, **396 at $10,000**. Concentrated in `usdt-btc-xrp-fwd` (169) and
`usdt-btc-xrp-rev` (155); their `gross_bps` at those moments averaged −28 to −32 bps, so nothing of
value was lost. The size-dependence (1 → 33 → 396) is the same §3 story from the other direction.

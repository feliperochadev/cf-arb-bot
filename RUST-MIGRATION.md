# Rust migration guide — living document

Per `cf-arb-bot-plan.md` §9: shipped alongside the Java implementation (this file was written during
the initial build, not retrofitted), settled in Phase 5 against real Phase 4 measurements. This is
**not a plan to execute** — it is the decision criterion, the port map, and the honest cost/benefit,
written down now so the eventual answer is a measurement, not a preference formed after the fact.

## 1. The decision criterion — stated in advance

**Migrate only if the Phase-4 HdrHistograms show in-process time (frame→decision) is a material
fraction of the total cycle time.**

The stated expectation, made explicit before any real production data exists: it will not be. The
end-to-end budget for one triangle leg is dominated by MEXC's REST round trip through a CDN edge —
measured from this development host at ~245ms TTFB through Akamai (`cf-arb-bot-plan.md` §3 step 1) —
against a sub-100µs in-process compute target. The smoke test run during this build measured
**frame-to-decision p50 = 5.7µs, p99 = 38-44µs** on the JVM, comfortably inside that budget with
enormous headroom. **The honest likely Phase 5 conclusion is "the JVM is not the bottleneck; the
region choice and the connection-reuse strategy are" — write that down now so it isn't rationalized
away later if the Rust work has already started for its own sake.**

## 2. Component-by-component port map

| Component | Port target | Effort | Notes |
|---|---|---|---|
| `feed.ProtobufWalker` | Manual iterative walker (no `prost`, matching the Java approach) | Small | ~150 lines; the algorithm is already schema-free and allocation-free, ports almost mechanically |
| `feed.MexcDepthDecoder` | Same structure, `&[u8]` slices instead of cursor objects | Small | Rust's borrow checker actually simplifies the "reusable cursor" pattern Java needed for zero allocation |
| `book.L2Book` | Fixed-size arrays, `Vec` avoided in the hot path | Trivial | Direct translation; binary-search insert/delete ports 1:1 |
| `strategy.Sizer` / `EdgeCalculator` | Direct translation, fixed-point via `i64` | Trivial | **Best candidate for a SHARED test-vector file** (JSON fixtures both languages read) so the port is provably identical to the Java model, not just "looks right" |
| `feed.MexcWsClient` | `tokio-tungstenite` | Medium | The reconnect/backoff/churn-tracking logic (ported from `recorder-service.VenueConnection`) must be re-derived; this is where MEXC's hard-won operational knowledge (§4's "10-minute outage" story) has to be re-encoded correctly |
| `exec.MexcRestClient` / `MexcSigner` | `reqwest` + `hmac`/`sha2` crates, pre-warmed connection pool | Small-Medium | HMAC-SHA256 signing is trivial; connection-pool pre-warming needs the same care as the Java `WebClient` version |
| `exec.CycleExecutor` / `Unwinder` | Direct translation | Medium | The leg-failure/unwind state machine is the highest-value logic to get right in either language; port it carefully, test it harder |
| Quarkus REST + Micrometer + health checks | `axum` + `metrics-rs` + hand-rolled health endpoint | **Large** | The single biggest chunk of throwaway scaffolding work — Quarkus gives this almost for free in Java |
| JCTools SPSC queues | `crossbeam-channel` or a hand-rolled ring | Small | Rust's ownership model may make some of this unnecessary — a single-producer-single-consumer bounded channel is standard-library-adjacent |

## 3. What Rust actually buys

- No GC pauses. The JVM's allocation-free hot path already avoids most GC pressure (measured: the
  bot ran the full smoke test without a discernible pause affecting the p99 latency figures above),
  but a stop-the-world pause during the ~200ms median opportunity window is still a real tail-risk
  Rust eliminates structurally.
- No JIT warmup. The first trades after every restart are the JVM's least-optimized ones — this
  matters more than usual here because `Restart=always` + a kill-switch trip means restarts are a
  real operational event, not a rare one.
- Meaningfully lower memory footprint (JVM heap + metaspace vs. a few MB of Rust binary), which
  could justify dropping the instance from `c7g.medium` to something smaller — a real, if modest,
  cost saving on top of the latency argument.

## 4. What it costs

- Re-deriving `feed.MexcProtocol`'s and `feed.MexcWsClient`'s hard-won operational knowledge
  (the "10-minute outage, 18 reconnects" story) in a second language, with a second chance to get
  the reconnect/churn/staleness logic subtly wrong.
- No Quarkus config-binding/health/metrics/REST scaffolding — all of that is hand-rolled.
- A second codebase to keep correct, tested, and in sync with any future MEXC API changes.
- The `exec.UserDataStream` listenKey flow is already flagged unverified in the Java build (needs
  live probing per `recorder-service`'s non-negotiable #4) — that verification work has to happen
  regardless of which language ends up running in production, so it is NOT a Rust-specific cost, but
  it is a cost that must be paid before EITHER implementation can be trusted with the private stream.

## 5. Migration strategy, if the Phase 5 measurement says to proceed

1. Port `strategy` and below first (protobuf walker → book → edge calculator/sizer) — the part with
   no I/O, and the part cross-checked in §2's shared test-vector file.
2. Run the Rust strategy layer **in shadow mode against the same live feed** as the Java bot,
   asserting identical fire/no-fire decisions on identical book states, before any Rust code is
   allowed near `exec`.
3. Only once that shadow run has accumulated enough decisions to be statistically meaningful
   (matching the same reconciliation discipline `cf-arb-bot-plan.md` §10 Phase 2 requires of the
   Java bot itself against the Python research pipeline), port `feed.MexcWsClient` and `exec`.
4. Keep the shared test-vector file as the permanent contract between implementations for as long
   as both exist.

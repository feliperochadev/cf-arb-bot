# cf-arb-bot

Live, MEXC-only, triangular-arbitrage proof-of-concept. Java 25 · Quarkus 3.36 · Vert.x/Netty event
loops · JCTools lock-free queues. Ships in **dry-run (paper trading) mode by default** — see
`CLAUDE.md` for the safety non-negotiables before touching this code. Full spec, findings, and
the Gate 0 pre-code measurements: `../cf-arb-bot-plan.md`.

## Why this exists

`cf-arb-poc` (the sibling Python research pipeline) found that triangular arbitrage exists on MEXC
and survives realistic latency, but the naive top-of-book simulation's headline numbers
($100 → $607.91) turned out to be unreachable at a $100 seed once real exchange lot-size
quantization is applied — some winning cycles require a minimum order of 1 whole SOL. This bot's
`strategy.EdgeCalculator` puts that arithmetic INSIDE the live edge calculation, so untradeable
triangles simply never fire rather than needing to be blacklisted by hand.

Visual representation of the SPSC Queue and triangular arbitrage detector workflow:

<img width="900" height="638" alt="image" src="https://github.com/user-attachments/assets/c0430b04-4aad-451d-91c2-6c5360e7cceb" />


## Architecture

```
feed.MexcWsClient  →  book.BookRegistry  →  strategy.OpportunityDetector  →  (SPSC queue)  →  exec.CycleExecutor
     (decode)             (L2 books)          (EdgeCalculator + RiskGates)                   (signed orders, place→reconcile)
```

One WebSocket connection (9 configured symbols, well under MEXC's 30-per-connection cap) decodes
`spot@public.aggre.depth.v3.api.pb@10ms` protobuf frames directly into per-symbol L2 books on the
Netty event loop — allocation-free, no `protobuf-java`. Every book update re-evaluates only the
triangles that touch the updated symbol (a precomputed inverted index), walking each leg's real
ladder, quantizing to MEXC's actual lot-size filters, and applying the real per-symbol taker fee.
A fire decision hands off to a dedicated executor thread for three sequential signed REST calls
(MEXC has no WebSocket order-entry API) — or, in dry-run mode, credits the already-honest computed
edge without touching the network at all.

**Deep dive:** [`Architecture-tour.md`](Architecture-tour.md) walks the full path frame-by-frame —
every module, component and class it touches, in flow order, with a diagram per stage and three
worked end-to-end scenarios (a non-opportunity tick, a profitable cycle, a broken cycle + unwind).
Brazilian Portuguese: [`Architecture-tour-pt.md`](Architecture-tour-pt.md).

## Setup

```bash
cd cf-arb-bot
./mvnw test              # 53 tests: fixed-point math (incl. a real overflow bug caught while
                          # porting cf-trader's mulDiv, and plain-decimal rendering for order params),
                          # a real-captured-frame protobuf decoder cross-check, HMAC signing, risk
                          # gates, book reset/reconnect, triangle-closure validation, an EdgeCalculator
                          # cross-check against the Python research pipeline's exact log-space formula,
                          # the full Unwinder/CycleExecutor failure matrix (partial fill, zero fill,
                          # wrong-leg reversal), kill-switch listener wiring, and journal escaping
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar   # dry-run by default -- connects to live MEXC market
                                                 # data (public, unauthenticated), places no orders
```

`config/mexc_filters.json` (MEXC's live lot-size/fee/price-precision schedule — public
`GET /api/v3/exchangeInfo`, no API key) ships bundled on the classpath
(`src/main/resources/config/mexc_filters.json`), so no separate deploy step is required; set
`cf-bot.filters-path` to a filesystem path to override it with a freshly re-fetched snapshot without
rebuilding. Re-fetch periodically — MEXC's fee promotions have an expiry (see
`../cf-arb-bot-plan.md` §2.2).

**Open wire-format question (does NOT block live-mode startup, just verification):** MEXC's
documented `type` ENUM for `POST /api/v3/order` is `LIMIT`/`MARKET`/`LIMIT_MAKER`/`IOC`/`FOK` —
confirmed directly against MEXC's own published spot v3 API reference — and there is no separate
`timeInForce` parameter at all. `cf-bot.exec.order-type` defaults to `IOC`. Confirmed live across
ALL 2074 MEXC spot symbols: `exchangeInfo`'s per-symbol `order_types` never lists `IOC`/`FOK` for
ANY symbol, so startup validation cannot confirm this by membership and instead logs a loud WARN
("unverified until the 1-USDT probe runs") rather than refusing to start. The credentialed 1-USDT
live probe (see CLAUDE.md #9's verify-before-trust discipline) is what actually settles whether
`IOC` is accepted for these symbols — this callout exists so nobody mistakes the WARN for silence.

## API (read-only — security rule S12)

- `GET /api/v1/state` — equity, PnL, kill-switch status, feed health
- `GET /api/v1/triangles` — each configured triangle's legs and book-readiness
- `GET /api/v1/config` — effective config (no secrets are ever in config to redact)
- `GET /api/v1/latency` — HdrHistogram percentiles: frame→decision, decision→leg1-ack, full cycle
- `GET /q/health/ready`, `GET /q/metrics` — Quarkus standard

## Going live (Phase 4 — see `../cf-arb-bot-plan.md` §10)

1. Create a MEXC API key: `SPOT_DEAL_WRITE` + `SPOT_ACCOUNT_READ` only, **no** withdrawal/transfer
   permission, IP-allowlisted to the deployed Elastic IP.
2. `MEXC_API_KEY` / `MEXC_API_SECRET` via environment (SSM SecureString in Tokyo) — never a config
   file.
3. `CF_BOT_DRY_RUN=false` — this is the ONLY supported way to enable live trading; the code default
   can never be flipped (S4). A prominent startup warning fires when you do this.
4. Fund the account with the configured seed (`cf-bot.capital.seed-usd`, default $100).
5. Supervise the run. The kill switch (`cf-bot.risk.equity-floor-usd`, default $50) halts trading
   permanently on a floor breach or repeated order failures — restart requires operator review, by
   design (S7).

## Infrastructure

`terraform/` — AWS `ap-northeast-1`, ~$38/month (no NAT gateway, no ALB — either would blow the
budget alone). **Never applied in this build** (no AWS credentials in the environment that wrote
it) — validated with `terraform validate` only (both `terraform/` and `terraform/modules/probe/`
pass cleanly). See `../cf-arb-bot-plan.md` §7 for the full rationale, including why the region
choice itself is still pending a real RTT probe (`terraform/modules/probe/`) — both MEXC endpoints
are CDN-fronted, so proximity to the edge is not proximity to the origin.

The read-only API has no security-group ingress at all — it stays loopback-only
(`%prod.quarkus.http.host=127.0.0.1`) and is reached exclusively via SSM port forwarding:
`aws ssm start-session --target <instance-id> --region ap-northeast-1 \
--document-name AWS-StartPortForwardingSession \
--parameters '{"portNumber":["8080"],"localPortNumber":["8080"]}'`, then browse
`http://localhost:8080/api/v1/state` locally.

**Deploy layout (REVIEW.md MAJ-07):** the systemd unit's `ExecStart` runs
`/opt/cf-arb-bot/quarkus-run.jar`, which is a thin bootstrap runner requiring its sibling `lib/`,
`app/`, and `quarkus/` directories (Quarkus 3.x's fast-jar layout) alongside it. The deploy step
must sync the ENTIRE `target/quarkus-app/` tree into `/opt/cf-arb-bot/` — e.g.
`rsync -a target/quarkus-app/ host:/opt/cf-arb-bot/` — copying `quarkus-run.jar` alone crashes with
`ClassNotFoundException: io.quarkus.bootstrap.runner.QuarkusEntryPoint`.

## Honest limits

See `../cf-arb-bot-plan.md` §12 in full. Headline ones: the Python research pipeline this bot's
model is cross-checked against cannot lose money by construction (100% win rate, 0% drawdown) — the
live loss distribution is new information this PoC has never measured. The re-baselined,
quantization-honest simulation (`cf-arb-poc/cfarb/gate0_rebaseline.py`) found realistic returns are
far smaller than the original headline: best observed was +0.98% over a 193-hour window, not the
original report's +507%.

## Rust migration

`RUST-MIGRATION.md` — a living document, not a plan to execute yet. The stated expectation is that
the JVM is not the bottleneck (the real latency budget is ~230ms of CDN-fronted REST round trips
against a sub-100µs in-process compute budget, which this smoke-tested at p50=5.7µs), so the honest
Phase 5 answer is likely "measure it, and don't migrate."

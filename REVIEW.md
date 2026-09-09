# cf-arb-bot Independent Comprehensive Code & Architecture Review

**Date:** 2026-09-07  
**Target:** `cf-arb-bot/` (Quarkus 3.36.1, Java 25, Vert.x / Netty, AWS Terraform deployment)  
**Scope:** Architecture logic, financial & arithmetic calculation, security posture, latency & performance on hot paths, and AWS PoC operational resilience.

---

## Executive Summary

An independent comprehensive review of `cf-arb-bot` was conducted against the target specification (`cf-arb-bot-plan.md`) and production HFT safety standards. 

While the codebase exhibits strong architectural intentions—specifically zero-allocation Protobuf parsing, fixed-point math (`FixedPoint`), lock-free ring buffers (`JCTools`), and fail-closed safety principles—**the current implementation cannot succeed in a live run on AWS and has critical defects that will either prevent the service from booting or lead to stranded capital and false 100% loss booking.**

Most critically:
1. **Live trading cannot boot at all**: The bot hardcodes `order-type=IMMEDIATE_OR_CANCEL` and validates that this exact string exists in MEXC's `order_types` array. In MEXC's (and Binance's) v3 spot API, `IOC` is a `timeInForce` parameter, not an `orderType` (which is `LIMIT`). Because MEXC advertises only `["LIMIT", "MARKET", "LIMIT_MAKER"]`, the startup safety check throws an `IllegalStateException` on boot whenever `dryRun=false`.
2. **Unwinding always fails and strands funds**: When reversing a failed leg, `Unwinder` submits a limit sell at the original leg's *ask* price boundary (or buy at the *bid* price). An IOC sell order placed above the market bid will **never fill** (zero-fill), causing the unwinder to abandon recovery, mark the capital as stranded, and book a catastrophic 100% loss while unhedged assets sit on the exchange.
3. **Resting orders remain open indefinitely**: The bot lacks any order cancellation capability (`cancelOrder`). If orders are placed as resting `LIMIT` orders, any partially filled or zero-filled leg leaves an open order on the matching engine while the bot attempts an unwind or trips the kill switch.
4. **AWS deployment aborts on first boot**: Terraform's `user_data.sh` will terminate prematurely if SSM Parameter Store secrets are not pre-populated out-of-band before `terraform apply`, leaving the EC2 instance with no systemd service or application directory.
5. **Feed watchdog kills the bot during low volume**: The 5-second connection watchdog trips the trading kill switch permanently instead of initiating a WebSocket reconnection.

Below is the detailed classification of findings into **Major**, **Medium**, and **Minor**, followed by deep-dive analyses across the 5 review dimensions and actionable fix suggestions.

---

## Summary of Findings

| ID | Severity | Category | Title | Affected Files |
|---|---|---|---|---|
| **MAJ-01** | 🔴 MAJOR | Logic / AWS | Live mode fails closed at boot due to `order-type=IMMEDIATE_OR_CANCEL` confusion with `timeInForce` | `BotService.java`, `BotConfig.java`, `CycleExecutor.java` |
| **MAJ-02** | 🔴 MAJOR | Logic / Calc | `Unwinder` places reversal orders on the passive side of the spread, guaranteeing 0% fill and stranded capital | `Unwinder.java`, `CycleExecutor.java` |
| **MAJ-03** | 🔴 MAJOR | Logic / Exec | Missing `cancelOrder` capability leaves resting limit orders open on exchange matching engine | `OrderReconciler.java`, `MexcRestClient.java`, `Unwinder.java` |
| **MAJ-04** | 🔴 MAJOR | Logic / Exec | Order placement rejections misclassified as `UNKNOWN`, causing false kill switch trips | `OrderReconciler.java`, `CycleExecutor.java` |
| **MAJ-05** | 🔴 MAJOR | AWS / Deploy | AWS `user_data.sh` fails cloud-init if SSM parameters do not pre-exist, preventing service install | `terraform/user_data.sh`, `terraform/main.tf` |
| **MAJ-06** | 🔴 MAJOR | Logic / Risk | Feed watchdog trips permanent kill switch on normal 5-second silence instead of reconnecting | `BotService.java`, `KillSwitch.java` |
| **MAJ-07** | 🔴 MAJOR | AWS / Deploy | Quarkus fast-jar directory layout omitted in deployment specification | `ops/cf-arb-bot.service`, `terraform/user_data.sh` |
| **MED-01** | 🟠 MEDIUM | Logic / Risk | Clock skew sampled periodically but never gates order firing (dead risk gate) | `RiskGates.java`, `BotService.java`, `OpportunityDetector.java` |
| **MED-02** | 🟠 MEDIUM | Calculation | Clock skew calculation includes full network RTT, distorting time delta | `BotService.java` |
| **MED-03** | 🟠 MEDIUM | Calculation | `Sizer.fillAsk` overstates quote notional on multi-level ladder walks | `Sizer.java`, `EdgeCalculator.java` |
| **MED-04** | 🟠 MEDIUM | Performance | `UserDataStream` leaks Netty `WebSocketClient` instances on every reconnect | `UserDataStream.java` |
| **MED-05** | 🟠 MEDIUM | Logic / Network | `UserDataStream.refreshListenKey` sends malformed HTTP PUT payload | `UserDataStream.java` |
| **MED-06** | 🟠 MEDIUM | Performance | `full.getBytes()` allocates heap memory on every market data frame (rule R1 breach) | `MexcWsClient.java` |
| **MED-07** | 🟠 MEDIUM | Calculation | `OrderReconciler` parses string quantities via IEEE-754 `double`, risking precision corruption | `OrderReconciler.java` |
| **MED-08** | 🟠 MEDIUM | Security | Unquoted heredoc in secret fetch script risks variable expansion and truncation | `terraform/user_data.sh` |
| **MED-09** | 🟠 MEDIUM | Performance / Ops | Local event journal grows unbounded without S3 sync or logrotate cleanup | `EventJournal.java`, `terraform/main.tf` |
| **MED-10** | 🟠 MEDIUM | Logic / Exec | `CycleExecutor` lacks `OrderIntent` staleness check, risking execution of stale candidates | `CycleExecutor.java` |
| **MIN-01** | 🟡 MINOR | Calculation | `OrderReconciler.fetchCommission` does not filter by `orderId` in trade array loop | `OrderReconciler.java` |
| **MIN-02** | 🟡 MINOR | Security | `MexcSigner` takes `String` secret despite documentation claiming `char[]` protection | `MexcSigner.java` |
| **MIN-03** | 🟡 MINOR | Logic / Ops | `ReadinessCheck` can report healthy before feed updates have been processed | `ReadinessCheck.java`, `L2Book.java` |
| **MIN-04** | 🟡 MINOR | Performance | Paper trading latency metric blends sign-and-discard iterations into single histogram | `CycleExecutor.java` |
| **MIN-05** | 🟡 MINOR | AWS / Ops | CloudWatch alarms only monitor EC2 hypervisor status, not bot crash or kill-switch events | `terraform/main.tf` |
| **MIN-06** | 🟡 MINOR | Security | Production REST endpoints lack authentication or rate limiting | `BotApiResource.java` |
| **MIN-07** | 🟡 MINOR | Logic / Book | `L2Book.maybePromote` trusts book on elapsed time alone without minimum update threshold | `L2Book.java` |

---

## Major Findings

### MAJ-01: Live Mode Wire Contract Blocker: `order-type=IMMEDIATE_OR_CANCEL` vs. `timeInForce`

- **Severity:** 🔴 MAJOR (Blocks PoC Live Execution)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/BotService.java:132-146`, `config/BotConfig.java:114-124`, `exec/CycleExecutor.java:214-222`, `src/main/resources/application.properties:68`
- **Description:**
  In `BotService.validateOrderTypeSupported()`, the bot checks if `config.exec().orderType()` exists in the `order_types` array of `mexc_filters.json` for every configured symbol.
  `application.properties` sets:
  ```properties
  cf-bot.exec.order-type=IMMEDIATE_OR_CANCEL
  ```
  However, on MEXC Spot API v3 (modeled directly on Binance Spot API v3), `IMMEDIATE_OR_CANCEL` is **not** an order type. The order types advertised in `exchangeInfo` are strictly `["LIMIT", "MARKET", "LIMIT_MAKER"]`. IOC is a `timeInForce` parameter passed alongside `type=LIMIT`:
  ```
  POST /api/v3/order?symbol=BTCUSDT&side=BUY&type=LIMIT&timeInForce=IOC&quantity=...&price=...
  ```
  Because the codebase treats `IMMEDIATE_OR_CANCEL` as the `type` parameter and does not send `timeInForce`, `BotService` throws:
  ```
  IllegalStateException: cf-bot.exec.order-type=IMMEDIATE_OR_CANCEL is not supported by MEXC for symbol 'BTCUSDT' (venue advertises [LIMIT, MARKET, LIMIT_MAKER]) -- refusing to start in live mode
  ```
  Furthermore, `CycleExecutor.buildOrderParams()` never serializes a `timeInForce` parameter. If an operator attempts to circumvent this by configuring `order-type=LIMIT`, MEXC will reject the order with error `-1102 ("Mandatory parameter 'timeInForce' was not sent")`.
- **Failure Impact:** The bot **cannot start** in live mode on AWS (`CF_BOT_DRY_RUN=false`). It enters an immediate systemd restart loop.
- **Remediation:**
  1. Add `timeInForce` to `BotConfig.ExecConfig` (default `"IOC"`).
  2. Set `orderType` default to `"LIMIT"`.
  3. Update `CycleExecutor.buildOrderParams` to include `&timeInForce=` whenever `orderType` is `"LIMIT"`.
  4. Update `BotService.validateOrderTypeSupported` to ensure `LIMIT` is supported by the symbol filter.

---

### MAJ-02: `Unwinder` Places Reversal Orders on Passive Side of Spread, Guaranteeing 0% Fill

- **Severity:** 🔴 MAJOR (Direct Capital Loss / Stranded Inventory)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/exec/Unwinder.java:70-85`, `exec/CycleExecutor.java:185-195`
- **Description:**
  When a multi-leg cycle fails on leg 1 or 2, `Unwinder.unwind()` steps backward through acquired assets to convert them back to the anchor asset.
  To size and price the reversal order, it executes:
  ```java
  Side reverseSide = sides[i] == Side.BID ? Side.ASK : Side.BID;
  SymbolFilter filter = filters[i];
  long priceFixed = state.legs[i].requestedPriceFixed; // <--- STALE ENTRY BOUNDARY PRICE

  long[] q = CycleExecutor.quantizeLeg(reverseSide, filter, currentAmount, priceFixed);
  reverseLeg.requestedPriceFixed = priceFixed;
  ```
  Consider what happens:
  1. Leg 0 was `Side.ASK` (BUY BTC with USDT) at price $77,850.
  2. Leg 1 fails. The bot holds BTC and needs to reverse Leg 0: `reverseSide = Side.BID` (SELL BTC for USDT).
  3. `Unwinder` sets `requestedPriceFixed = 77850.0`.
  4. When Leg 0 was bought off the ask at $77,850, the market bid was lower (e.g., $77,840). By the time Leg 1 fails and unwind is called, the market bid might be $77,830.
  5. The bot submits an IOC (or Limit) **SELL** order with price **$77,850**.
  6. An IOC SELL order with limit price $77,850 matched against bids <= $77,840 will **immediately expire with 0 fills**!
  7. `Unwinder` checks:
     ```java
     if (reverseLeg.status != CycleState.LegStatus.FILLED && reverseLeg.status != CycleState.LegStatus.PARTIAL) {
         LOG.errorf("[unwind] ... reversal did not fill ... remains STRANDED");
         return new Result(anyOrderPlaced, 0, "reversal did not fill");
     }
     ```
  8. `Unwinder` returns `recoveredAnchorFixed = 0`.
  9. `CycleExecutor.handleBrokenCycle` calculates:
     ```java
     long loss = r.recoveredAnchorFixed() - spent; // e.g. 0 - 100 = -100 USDT
     portfolio.applyBrokenCyclePnl(loss);
     ```
- **Failure Impact:** The unwinder will **fail on 100% of live reversals**. The bot books an immediate 100% loss of initial capital, trips the equity floor kill switch, and strands real crypto assets in the exchange account without any limit or stop order protecting them.
- **Remediation:**
  Reversal orders cannot reuse the entry price boundary:
  - If placing limit IOC reversals, the price must cross the spread (e.g. read the current best bid/ask from `BookRegistry` or apply a configurable emergency unwind slippage buffer, such as 50–100 bps beyond the last known price).
  - Alternatively, support `type=MARKET` for emergency unwinds so that the matching engine sweeps available liquidity immediately.

---

### MAJ-03: Missing `cancelOrder` Capability Leaves Resting Orders on Exchange

- **Severity:** 🔴 MAJOR (Uncontrolled Position Exposure)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/exec/OrderReconciler.java`, `exec/MexcRestClient.java`, `exec/CycleExecutor.java`
- **Description:**
  There is no `cancelOrder` method implemented anywhere in `MexcOrderApi` or `MexcRestClient`.
  If an order is placed as a `LIMIT` order without IOC (or if an IOC order is rejected and the bot falls back to standard Limit execution):
  1. If `reconciler.submitAndReconcile` times out after `legTimeoutMs` (1500ms) while an order is partially filled or unfilled, the order remains live and resting in the MEXC order book.
  2. The bot proceeds to `handleBrokenCycle` and calls `Unwinder`.
  3. While `Unwinder` is executing, the resting order on the exchange may be filled by another market participant.
  4. The bot now has duplicate exposure: it has unwound the partial fill, but the remainder just filled, stranding newly acquired non-anchor assets without tracking them.
- **Failure Impact:** Uncontrolled open positions on MEXC, portfolio desynchronization, and stranded inventory.
- **Remediation:**
  Implement `DELETE /api/v3/order` in `MexcRestClient`. Before declaring an order terminal as `PARTIAL` or `ZERO_FILL` or proceeding to unwind, issue an explicit cancellation request for the `clientOrderId` and wait for confirmation.

---

### MAJ-04: Order Placement Rejections Misclassified as `UNKNOWN`, Causing False Kill Switch Trips

- **Severity:** 🔴 MAJOR (Spurious Bot Halts)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/exec/OrderReconciler.java:45-72`, `exec/CycleExecutor.java:163-172`
- **Description:**
  In `OrderReconciler.submitAndReconcile`:
  ```java
  try {
      String resp = rest.placeOrder(params, legTimeoutMs).get(legTimeoutMs, TimeUnit.MILLISECONDS);
      placedOrderId = readTextField(resp, "orderId");
  } catch (Exception e) {
      logAttemptFailure("place", symbol, leg.clientOrderId, e);
      // Fall through to reconciliation regardless
  }

  try {
      String resp = rest.queryOrder(symbol, leg.clientOrderId, legTimeoutMs).get(legTimeoutMs, TimeUnit.MILLISECONDS);
      // parse executedQty ...
  } catch (Exception e) {
      logAttemptFailure("reconcile", symbol, leg.clientOrderId, e);
      leg.status = CycleState.LegStatus.UNKNOWN;
      return;
  }
  ```
  If `placeOrder` is rejected by MEXC (e.g., HTTP 400 due to temporary balance check, filter validation, or lot size), `placedOrderId` remains `null`.
  The code falls through to `queryOrder(symbol, leg.clientOrderId)`.
  Because the order was never accepted by the matching engine, MEXC responds to `GET /api/v3/order` with:
  ```json
  {"code": -2013, "msg": "Order does not exist."}
  ```
  `signedGet` receives a non-2xx status (HTTP 400) and throws `OrderRejectedException`.
  The catch block catches this exception and unconditionally sets:
  ```java
  leg.status = CycleState.LegStatus.UNKNOWN;
  ```
  In `CycleExecutor`:
  ```java
  case UNKNOWN -> {
      metrics.recordCycleBroken();
      killSwitch.recordFailure("leg-" + leg + "-reconciliation-unknown (" + triangle.name() + ")");
      // HALTS TRADING, requires operator review
      return;
  }
  ```
- **Failure Impact:** Any ordinary order rejection (which should simply count as a `ZERO_FILL` and unwind/fail cleanly) is misclassified as an unknown emergency, tripping the kill switch and halting the bot.
- **Remediation:**
  In `OrderReconciler`, inspect the error response from `queryOrder`. If `OrderRejectedException` contains error code `-2013` ("Order does not exist") and `placeOrder` also failed with a 4xx error (not a timeout), classify the order state definitively as `ZERO_FILL` or `REJECTED_PRESUBMIT`.

---

### MAJ-05: AWS `user_data.sh` Fails Cloud-Init if SSM Parameters Do Not Pre-Exist

- **Severity:** 🔴 MAJOR (AWS Deployment Failure)
- **Files:** `cf-arb-bot/terraform/user_data.sh:40-60`, `terraform/main.tf:165-175`
- **Description:**
  `terraform/main.tf` explicitly does not create the SSM Parameter Store parameters `/cf-arb-bot/mexc-api-key` and `/cf-arb-bot/mexc-api-secret` (noting they must be created out-of-band).
  However, in `terraform/user_data.sh`:
  ```bash
  set -euo pipefail
  ...
  /usr/local/sbin/cf-arb-bot-fetch-secrets.sh # populate once now, before the unit is ever started
  ```
  At instance boot, `user_data.sh` executes this script. If an operator applies Terraform before populating the parameters, `aws ssm get-parameter` fails. Because `set -e` is active, the entire `user_data.sh` script aborts at line 58.
  As a result:
  - Directory `/opt/cf-arb-bot` is never created.
  - The systemd service `/etc/systemd/system/cf-arb-bot.service` is never written.
  - Cloud-init is marked as failed.
- **Failure Impact:** Standard `terraform apply` fails to bootstrap a functional instance.
- **Remediation:**
  In `cf-arb-bot-fetch-secrets.sh`, allow graceful degradation: check if the parameters exist; if not, check if `CF_BOT_DRY_RUN=true` or write dummy credentials with a prominent warning so bootstrap succeeds. In `user_data.sh`, wrap the initial fetch in an error check or document the prerequisite order.

---

### MAJ-06: Feed Watchdog Trips Permanent Kill Switch on Normal 5-Second Silence

- **Severity:** 🔴 MAJOR (Operational Fragility)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/BotService.java:40-42, 175-195`, `risk/KillSwitch.java:88-95`
- **Description:**
  `BotService` schedules a feed watchdog running every 5 seconds:
  ```java
  private static final long CONNECTION_DEAD_THRESHOLD_NANOS = 5_000_000_000L; // 5 seconds
  ...
  boolean feedDead = anyWarmed && freshestWarmedBookAgeNanos > CONNECTION_DEAD_THRESHOLD_NANOS;
  if ((feedDead && wsClient.isConnected()) || wsClient.isChurning()) {
      killSwitch.recordFeedUnhealthy(feedDead ? "sustained-stale-book" : "connection-churning");
  }
  ```
  When `killSwitch.recordFeedUnhealthy()` is called, `KillSwitch.trip()` permanently latches `tripped = true`. The kill switch has no automated recovery.
  The universe consists of 9 symbols, including illiquid cross pairs such as `XRPETH` and `XRPBTC`. During off-peak hours, it is entirely possible for 5 seconds to elapse without a depth update across this group.
- **Failure Impact:** A transient 5-second market quietness or brief network packet loss trips the kill switch permanently, terminating all trading until a human operator intervenes and restarts the JVM.
- **Remediation:**
  1. A connection-level stall should trigger a WebSocket disconnect/reconnect cycle (`wsClient.reconnect()`), not a permanent kill-switch latch.
  2. The silence threshold should be increased (e.g. 30–60 seconds) before considering the connection dead.
  3. Only trip the kill switch if reconnection fails repeatedly (e.g. `reconnectAttempts >= 5`).

---

### MAJ-07: Quarkus Fast-Jar Directory Layout Omitted in Deployment Specification

- **Severity:** 🔴 MAJOR (AWS Deployment Failure)
- **Files:** `cf-arb-bot/ops/cf-arb-bot.service:17`, `terraform/user_data.sh:82`, `pom.xml`
- **Description:**
  The systemd unit specifies:
  ```ini
  ExecStart=/usr/bin/java -Xms512m -Xmx512m -XX:+AlwaysPreTouch -XX:MaxDirectMemorySize=256m \
    -jar /opt/cf-arb-bot/quarkus-run.jar
  ```
  In Quarkus 3.x, the default build output in `target/quarkus-app/` produces:
  - `quarkus-run.jar` (thin bootstrap runner)
  - `lib/boot/` and `lib/main/` (all dependency jars)
  - `app/` (application jar)
  - `quarkus/` (generated bytecode and metadata)
  `quarkus-run.jar` cannot run standalone; it expects `lib/`, `app/`, and `quarkus/` in the same directory. If a deployment process copies only `quarkus-run.jar` into `/opt/cf-arb-bot/`, the application will crash with `ClassNotFoundException: io.quarkus.bootstrap.runner.QuarkusEntryPoint`.
- **Failure Impact:** Service fails to start on EC2.
- **Remediation:**
  Update deployment documentation and `user_data.sh` to explicitly require extracting or rsyncing the entire `target/quarkus-app/` directory into `/opt/cf-arb-bot/`.

---

## Medium Findings

### MED-01: Clock Skew Sampled Periodically But Never Gates Order Firing

- **Severity:** 🟠 MEDIUM (Unenforced Safety Gate)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/risk/RiskGates.java:80-110`, `BotService.java:160-174`, `strategy/OpportunityDetector.java:55-80`
- **Description:**
  `BotService` samples exchange server time every 60 seconds and updates `clockSkewNanos`. `ReadinessCheck` verifies `clockOk`.
  However, in `RiskGates.canFire()` and `OpportunityDetector.evaluate()`, `clockSkewNanos` is **never inspected**.
  If local NTP drifts or EC2 time sync experiences a jump > `recvWindow` (5000ms), `canFire()` returns `true`, and the bot proceeds to submit orders. All signed requests will then fail on MEXC with error `-1021 ("Timestamp for this request was outside of the recvWindow")`.
- **Remediation:** Pass `BotService.clockSkewNanos()` into `RiskGates.canFire()` and fail closed if `Math.abs(clockSkewNanos) > clockSkewToleranceNanos`.

---

### MED-02: Clock Skew Measurement Algorithm Includes Full Network RTT

- **Severity:** 🟠 MEDIUM (Measurement Inaccuracy)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/BotService.java:167-172`
- **Description:**
  ```java
  restClient.serverTime().whenComplete((serverMs, err) -> {
      ...
      long localMs = System.currentTimeMillis();
      clockSkewNanos = (localMs - serverMs) * 1_000_000L;
  });
  ```
  `localMs` is captured only *after* the asynchronous HTTP response arrives. If the HTTP call takes 120ms round-trip, `localMs` is already ahead of `serverMs` by the server execution time plus outbound/inbound transmission latency. The calculated clock skew will fluctuate wildly based on network jitter rather than true NTP drift.
- **Remediation:** Record `localStartMs` immediately before calling `serverTime()`. When the response returns, compute `rtt = localEndMs - localStartMs` and estimate skew as `(localStartMs + rtt / 2) - serverMs`.

---

### MED-03: `Sizer.fillAsk` Overstates Quote Notional on Multi-Level Walks

- **Severity:** 🟠 MEDIUM (Calculation Leak)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/strategy/Sizer.java:70-96`
- **Description:**
  In `Sizer.fillAsk`:
  ```java
  baseFilled = FixedPoint.quantizeDown(baseFilled, filter.qtyStep());
  long actualQuoteNotional = FixedPoint.mulDiv(baseFilled, worstPrice, FixedPoint.SCALE);
  out.quoteFixed = actualQuoteNotional;
  ```
  `worstPrice` is the price of the deepest level consumed. If level 0 was consumed at price 100 and level 1 was partially consumed at price 102, `actualQuoteNotional` computes `baseFilled * 102`, which is strictly greater than the true quote currency spent across the ladder.
  While conservative for minimum notional checks, setting `out.quoteFixed` to this value causes downstream modules to misstate the expected spend of the leg.
- **Remediation:** Re-walk or proportionally scale the accumulated `quoteSpent` to reflect the quantized `baseFilled`, rather than multiplying by `worstPrice`.

---

### MED-04: `UserDataStream` Leaks Netty `WebSocketClient` on Reconnect

- **Severity:** 🟠 MEDIUM (Resource / Memory Leak)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/exec/UserDataStream.java:74-95`
- **Description:**
  In `UserDataStream.connectWs()`:
  ```java
  private void connectWs() {
      WebSocketClientOptions opts = new WebSocketClientOptions().setSsl(true).setTcpKeepAlive(true);
      WebSocketClient client = vertx.createWebSocketClient(opts); // <--- ALLOCATES CLIENT
      ...
      ws.closeHandler(v -> {
          if (running) vertx.setTimer(5_000, id -> connectWs());
      });
  }
  ```
  Every reconnection attempt allocates a new `WebSocketClient` without closing the previous one. Over an extended run with unstable connectivity, this leaks Netty channel pools and file descriptors.
- **Remediation:** Instantiate `WebSocketClient` once during service startup and reuse it across reconnection attempts.

---

### MED-05: `UserDataStream.refreshListenKey` Sends Malformed HTTP PUT Payload

- **Severity:** 🟠 MEDIUM (API Protocol Defect)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/exec/UserDataStream.java:62-72`
- **Description:**
  In `refreshListenKey`:
  ```java
  restClient.put("/api/v3/userDataStream")
      .putHeader("X-MEXC-APIKEY", apiKey)
      .sendBuffer(Buffer.buffer("listenKey=" + listenKey), ar -> ...);
  ```
  No `Content-Type: application/x-www-form-urlencoded` header is attached. On MEXC Spot API v3, `PUT /api/v3/userDataStream` expects `listenKey` either as a query parameter (`/api/v3/userDataStream?listenKey=...`) or in a properly formatted form body. Without this, the server returns 400 or ignores the keepalive, causing the listenKey to expire after 60 minutes.
- **Remediation:** Append `?listenKey=` as a query parameter or add the `Content-Type` header.

---

### MED-06: Hot Path Allocation: `full.getBytes()` Copies Every Frame on Netty Event Loop

- **Severity:** 🟠 MEDIUM (Latency Jitter / GC Allocation)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/feed/MexcWsClient.java:188`
- **Description:**
  On the Netty event loop in `onWsFrame`:
  ```java
  byte[] bytes = full.getBytes();
  ```
  `full.getBytes()` creates a defensive heap copy of every incoming WebSocket frame. For 9 active symbols receiving depth frames every 10–40ms, this generates 200–500 heap allocations per second on the market-data ingress thread. This contradicts Non-Negotiable Rule #2 ("zero allocation on the tick path") and introduces GC pause jitter.
- **Remediation:** Implement a `ByteBuf` or `Buffer` accessor in `ProtobufWalker` and `ByteScan` to read directly from Vert.x `Buffer.getByte(index)` without allocating intermediate arrays.

---

### MED-07: `OrderReconciler` Parses String Quantities via IEEE-754 `double`

- **Severity:** 🟠 MEDIUM (Precision / Rounding Hazard)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/exec/OrderReconciler.java:55-60, 78-95`
- **Description:**
  In `OrderReconciler.submitAndReconcile` and `fetchCommission`:
  ```java
  leg.executedBaseQtyFixed = FixedPoint.fromDouble(doubleField(node, "executedQty"));
  leg.executedQuoteFixed = FixedPoint.fromDouble(doubleField(node, "cummulativeQuoteQty"));
  ```
  MEXC returns quantities and prices as exact decimal strings (e.g. `"0.00001235"`). Converting them first to `double` and then rounding with `FixedPoint.fromDouble()` re-introduces binary floating-point representation error. `FixedPoint.parse(CharSequence)` was built specifically to avoid this.
- **Remediation:** Use `FixedPoint.parse(node.get("executedQty").asText())` directly.

---

### MED-08: Unquoted Heredoc in `user_data.sh` Secret Retrieval Script

- **Severity:** 🟠 MEDIUM (Security / Deployment Hazard)
- **Files:** `cf-arb-bot/terraform/user_data.sh:50-55`
- **Description:**
  In `cf-arb-bot-fetch-secrets.sh`:
  ```bash
  cat > "$OUT" <<ENVFILE
  MEXC_API_KEY=$MEXC_API_KEY
  MEXC_API_SECRET=$MEXC_API_SECRET
  ENVFILE
  ```
  Because `ENVFILE` is unquoted, bash performs parameter expansion when the script runs. If an API secret generated by MEXC contains a `$` character (or backslashes), bash will attempt to expand it as an environment variable, resulting in an empty or corrupted secret written to `secrets.env`.
- **Remediation:** Write the environment file using `printf`:
  ```bash
  printf 'MEXC_API_KEY=%s\nMEXC_API_SECRET=%s\n' "$MEXC_API_KEY" "$MEXC_API_SECRET" > "$OUT"
  ```

---

### MED-09: Local Event Journal Grows Unbounded Without S3 Sync or Logrotate

- **Severity:** 🟠 MEDIUM (Operational Stability)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/journal/EventJournal.java:23-35`, `terraform/main.tf:190-210`
- **Description:**
  `EventJournal` rotates files hourly under `/var/lib/cf-arb-bot/journal/`. S3 sync is explicitly documented as not yet implemented. On a 20GB root EBS volume, continuous journal writes will eventually exhaust available disk space, causing file write failures and system crashes.
- **Remediation:** Add a logrotate configuration in `user_data.sh` to compress and purge local journal files older than 7 days, or implement basic periodic S3 sync via AWS CLI in a systemd timer.

---

### MED-10: `CycleExecutor` Lacks `OrderIntent` Staleness Check

- **Severity:** 🟠 MEDIUM (Execution Quality / Adverse Selection)
- **Files:** `cf-arb-bot/src/main/java/io/cfarb/exec/CycleExecutor.java:82-95`
- **Description:**
  `CycleExecutor.runLoop()` polls `orderQueue`. If the executor thread experiences a delay (e.g. a 200ms lock-support park, REST timeout on a previous cycle, or JVM pause), it picks up and executes the queued `OrderIntent` without checking how much time has elapsed since `intent.detectedAtNanos()`. In triangular arbitrage where opportunities vanish in 50–150ms, executing an intent that is 300ms old almost guarantees leg failures and slippage.
- **Remediation:** Add a freshness gate at the start of `execute()`:
  ```java
  if (System.nanoTime() - intent.detectedAtNanos() > MAX_INTENT_AGE_NANOS) {
      metrics.recordIntentExpired();
      journal.write(JournalEvents.brokenCycle(triangle.name(), 0, "stale-intent", 0, portfolio.equity()));
      return;
  }
  ```

---

## Minor Findings

### MIN-01: `OrderReconciler.fetchCommission` Does Not Filter by `orderId` in Loop
- **Location:** `OrderReconciler.java:80-92`
- **Details:** When iterating through `GET /api/v3/myTrades`, the code sums `doubleField(t, "commission")` for all elements in the response array. If MEXC's endpoint ignores the `orderId` query parameter and returns account trades, commissions from unrelated trades would be summed into this leg's state.
- **Fix:** Add `if (t.hasNonNull("orderId") && !orderId.equals(t.get("orderId").asText())) continue;`.

### MIN-02: `MexcSigner` Accepts `String` Secret Despite Javadoc Claims
- **Location:** `MexcSigner.java:23-30`
- **Details:** The class javadoc claims the secret is stored exclusively in a `char[]` to prevent heap dump exposure, but the constructor parameter is `String apiSecret`.
- **Fix:** Update the javadoc or accept `char[]` / `byte[]` and zero out arrays after `SecretKeySpec` construction.

### MIN-03: `ReadinessCheck` Can Report Healthy Before Any Updates Arrive
- **Location:** `ReadinessCheck.java:38-48`, `L2Book.java:140-145`
- **Details:** If 30 seconds elapse while the connection is quiet, `L2Book.maybePromote` sets `trusted = true` based on time alone, even if `updateCount == 0`. `ReadinessCheck` will report `ready = true` on empty books.
- **Fix:** Require `book.updateCount() >= warmupUpdates && book.isTrusted()`.

### MIN-04: Paper Trading Latency Metric Blends Sign-and-Discard Iterations
- **Location:** `CycleExecutor.java:125-135`
- **Details:** In `signAndDiscardForLatencyMeasurement`, legs 0, 1, and 2 are each recorded into `metrics.recordDecisionToLeg1AckNanos()`. This records three samples per cycle into a histogram named specifically for leg 1.
- **Fix:** Record only leg 0, or record separate per-leg histograms.

### MIN-05: CloudWatch Alarms Only Monitor EC2 Hypervisor Status
- **Location:** `terraform/main.tf:230-245`
- **Details:** The sole alarm is `StatusCheckFailed`. If `cf-arb-bot` service crashes, fails its readiness check, or trips the kill switch, no CloudWatch alert or SNS notification is emitted.
- **Fix:** Add a CloudWatch metric filter on systemd journal logs for `KILL SWITCH TRIPPED` or add an alert on `/q/health/ready`.

### MIN-06: Production REST Endpoints Lack Authentication or Rate Limiting
- **Location:** `BotApiResource.java:25-90`
- **Details:** The management endpoints (`/api/v1/state`, `/api/v1/config`) have no token authentication. While production binds to loopback (`127.0.0.1`), any process or user on the instance (or tunnel) has full unauthenticated access to system status and config.
- **Fix:** Add a bearer token check or API key header requirement.

### MIN-07: Relocated Dependency in `pom.xml`
- **Location:** `pom.xml:74-78`
- **Details:** Maven outputs a build warning: `io.quarkus:quarkus-junit5` has been relocated to `io.quarkus:quarkus-junit`.
- **Fix:** Update artifact ID in `pom.xml`.

---

## Deep-Dive Analysis by Dimension

### 1. Logic Leaks in Architecture & State Management
- **Order State Machine Gap:** In `CycleExecutor`, the transition between leg execution, reconciliation, and unwinding assumes that every failed leg leaves the account in a known state. However, because reconciliation queries the exchange after placement fails without canceling open orders (MAJ-03), the matching engine may execute trades asynchronously while the bot has moved on to unwinding.
- **Single-Anchor Assumption vs. Multi-Currency Reality:** `Portfolio.java` models equity as a single scalar `equityFixed` in USDT. In reality, a broken cycle or partial fill leaves balances in BTC, ETH, or XRP. If an unwind order fails, those assets remain in the account, but `Portfolio` docks the entire spent amount from USDT equity, treating it as a total loss. On restart, the bot attempts to spend its configured seed in USDT, completely unaware of the non-anchor assets in the exchange account.

### 2. Calculation Leaks & Math / Precision Flaws
- **Sizer VWAP Quote Notional (MED-03):** In `Sizer.fillAsk()`, multiplying the total quantized base quantity by the worst price level (`worstPrice`) overstates the quote currency required to fill the order across a multi-level ladder.
- **Double Rounding in OrderReconciler (MED-07):** The project enforces 1e8 fixed-point arithmetic (`FixedPoint`) across the engine to prevent IEEE-754 precision issues. However, `OrderReconciler` bypasses `FixedPoint.parse(CharSequence)` and converts JSON fields (`executedQty`, `cummulativeQuoteQty`, `commission`) via `asDouble()`. For high-precision tokens (like BTC with 8 decimals), binary floating-point representation can introduce 1-Satoshi rounding errors that cause subsequent quantity checks to fail.

### 3. Security Leaks & Threat Analysis
- **Secrets on Disk:** In `terraform/user_data.sh`, secrets are written to `/etc/cf-arb-bot/secrets.env`. While permissions are set to `0400 cfarbbot:cfarbbot`, writing plaintext exchange API credentials to an unencrypted or EBS-backed root volume poses risk if snapshots or volume forensics are taken.
- **Broad KMS Decrypt Policy:** In `terraform/main.tf`, the IAM policy grants `kms:Decrypt` on `Resource = "*"`. Although constrained by `kms:ViaService = ssm.ap-northeast-1.amazonaws.com`, it allows the bot instance to decrypt any SSM parameter in the account, rather than restricting access to the specific KMS key used for the bot's secrets.

### 4. Performance & Latency Bottlenecks
- **Event Loop Allocations (MED-06):** In `MexcWsClient.onWsFrame()`, calling `full.getBytes()` creates a fresh byte array on every depth frame. Under high market activity (hundreds of frames per second), this generates substantial garbage collection churn on the Netty event loop, leading to latency spikes.
- **REST Round-Trip Serialization:** Each triangle execution requires 3 sequential legs. Each leg performs:
  1. `placeOrder` (~50–80ms)
  2. `queryOrder` (~50–80ms)
  3. `listTrades` (~50–80ms)
  Total latency per leg is 150–240ms, making a 3-leg cycle take ~450–720ms. In high-frequency triangular arbitrage, an opportunity rarely lasts longer than 150–200ms. By leg 2 and leg 3, the market has almost certainly moved.

### 5. AWS PoC Operational Resilience
- **SSM Bootstrap Deadlock (MAJ-05):** Cloud-init fails if SSM secrets are not manually created prior to running Terraform.
- **Watchdog Sensitivity (MAJ-06):** A 5-second market lull trips the kill switch permanently.
- **Quarkus Fast-Jar Packaging (MAJ-07):** Systemd service will crash if only the single `quarkus-run.jar` file is copied to `/opt/cf-arb-bot`.
- **Egress Security Group:** In `terraform/main.tf`, egress is restricted to ports 443, 123 (UDP), and 53 (DNS to `10.42.0.2`). This is well-hardened, but relies on Amazon-provided DNS resolver at base+2. If AL2023 utilizes link-local resolver `169.254.169.253`, link-local rules apply directly.

---

## Recommended Action Plan

### Phase 1: Fix Blockers for Live Execution (Must Fix Before Any Live Run)
1. **Fix MAJ-01 (Order Type & timeInForce):**
   - Update `BotConfig.ExecConfig` to define `timeInForce` (default `"IOC"`).
   - In `CycleExecutor.buildOrderParams`, set `type=LIMIT` and append `&timeInForce=IOC`.
   - Update `BotService.validateOrderTypeSupported` to check for `LIMIT` support.
2. **Fix MAJ-02 (Unwinder Reversal Pricing):**
   - In `Unwinder`, do not reuse entry prices for reversal orders.
   - For an unwind SELL, query the current book or submit with aggressive slippage (or `type=MARKET` if supported) to ensure instantaneous execution.
3. **Fix MAJ-03 (Implement `cancelOrder`):**
   - Add `DELETE /api/v3/order` to `MexcRestClient`.
   - Explicitly cancel open orders on timeout or partial fill before attempting an unwind.
4. **Fix MAJ-04 (Order Rejection Handling):**
   - In `OrderReconciler`, handle error code `-2013` as a confirmed `ZERO_FILL` when placement was rejected, preventing false trips to `UNKNOWN`.

### Phase 2: Fix AWS Bootstrap & Operational Stability
1. **Fix MAJ-05 (Cloud-init Bootstrap):**
   - Update `terraform/user_data.sh` to handle missing SSM parameters gracefully when in dry-run mode.
2. **Fix MAJ-06 (Watchdog Sensitivity):**
   - Change feed watchdog to trigger `wsClient.reconnect()` instead of permanently tripping `KillSwitch`. Increase timeout to 30 seconds.
3. **Fix MAJ-07 (Deployment Packaging):**
   - Document and package the full `target/quarkus-app/` directory tree in deployment instructions.

### Phase 3: Hardening & Performance Polish
1. **Enforce Clock Skew Gate (MED-01, MED-02):**
   - Incorporate `clockSkewNanos` check into `RiskGates.canFire()` and fix RTT calculation in `serverTime()`.
2. **Eliminate Frame Byte Copying (MED-06):**
   - Update `ProtobufWalker` to read directly from Vert.x `Buffer` without `getBytes()`.
3. **Fix Fixed-Point Parsing in Reconciliation (MED-07):**
   - Use `FixedPoint.parse()` on JSON text fields in `OrderReconciler`.
4. **Implement Journal Rotation / Archival (MED-09):**
   - Add automated logrotate or S3 sync cron for journal files.

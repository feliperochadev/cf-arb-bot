package io.cfarb.risk;

import io.cfarb.state.Portfolio;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Security rule S7: "Kill switch: feeds-unhealthy or repeated order errors must trip trading off
 * until operator action." Named in {@code cf-trader}'s rules but never implemented there
 * (cf-arb-bot-plan.md §5.5) — this is a from-scratch build, and with a $100 seed and three
 * dependent legs it matters more than any latency optimization in this codebase.
 *
 * <p>Once tripped, {@link #tripped()} latches permanently true. There is no automatic reset — S7
 * says "until operator action," and a bot that can silently un-trip itself after an equity-floor
 * breach is not a kill switch. Restart the process (after human review) to clear it.
 *
 * <p>The equity floor here has ZERO empirical basis from the offline simulation
 * (cf-arb-bot-plan.md §5.5, §12): the Python backtest cannot lose money by construction
 * (win_rate=1.0, max_drawdown=0.00 on every row), so this floor protects against exactly the
 * failure modes — leg failure, partial fill, reject, slippage — that model never represented.
 * Expect the live loss distribution to be the biggest surprise of Phase 4.
 */
public final class KillSwitch {

    private static final Logger LOG = Logger.getLogger(KillSwitch.class);

    private final AtomicBoolean tripped = new AtomicBoolean(false);
    private final AtomicReference<String> tripReason = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Consumer<String> tripListener;

    private final Portfolio portfolio;
    private final long equityFloorFixed;
    private final int maxConsecutiveFailures;

    public KillSwitch(Portfolio portfolio, long equityFloorFixed, int maxConsecutiveFailures) {
        this.portfolio = portfolio;
        this.equityFloorFixed = equityFloorFixed;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
    }

    /** Register the one action to run exactly once, on the CAS-winning trip transition
     * (cf-arb-bot-review-plan.md Tier 1 step 1.8: the previous kill switch only flipped a boolean —
     * no journal event, no metric, no operator signal). {@code BotService} wires this to a
     * {@code risk_trip} journal write, a metric increment, and a log line loud enough to page an
     * operator; readiness (Tier 2 step 2.4) reads {@link #tripped()} directly. There is no automated
     * "cancel and unwind on trip" beyond what {@code exec.CycleExecutor}'s own per-cycle broken-cycle
     * path already does — a trip caused by something other than a broken cycle (feed unhealthy,
     * consecutive failures) has no cycle-scoped inventory to unwind, and this bot does not yet
     * independently reconcile live account balances (Tier 3: real balance reconciliation) to know
     * what, if anything, it would be unwinding. */
    public void setTripListener(Consumer<String> listener) {
        this.tripListener = listener;
    }

    public boolean tripped() {
        return tripped.get();
    }

    public String tripReason() {
        return tripReason.get();
    }

    /** Call after every equity change. Trips (once, permanently) if equity has hit the floor. */
    public void checkEquityFloor() {
        if (portfolio.equity() <= equityFloorFixed) {
            trip("equity-floor-breached: " + portfolio.equity() + " <= " + equityFloorFixed);
        }
    }

    /** Call on a successful order/cycle to reset the consecutive-failure counter. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
    }

    /** Call on any order rejection, timeout, or broken cycle. Trips after maxConsecutiveFailures
     * in a row — the same signal S7 names ("repeated order errors"). */
    public void recordFailure(String reason) {
        int n = consecutiveFailures.incrementAndGet();
        if (n >= maxConsecutiveFailures) {
            trip("consecutive-failures(" + n + "): " + reason);
        }
    }

    /** Feed-health gate: call whenever a book's health/staleness status changes. */
    public void recordFeedUnhealthy(String reason) {
        trip("feed-unhealthy: " + reason);
    }

    private void trip(String reason) {
        if (tripped.compareAndSet(false, true)) {
            tripReason.set(reason);
            Consumer<String> listener = tripListener;
            if (listener != null) {
                try {
                    listener.accept(reason);
                } catch (Throwable t) {
                    // A listener failure must never mask the trip itself, or hide it from whatever
                    // part of the listener DID succeed before throwing.
                    LOG.errorf(t, "kill switch trip listener threw for reason: %s", reason);
                }
            }
        }
    }
}

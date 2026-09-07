package io.cfarb.state;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks compounding equity in the anchor stablecoin (1e8-fixed units). Single-writer: only the
 * executor thread mutates equity after a cycle completes (or fails and is unwound); readers (the
 * risk gates on the detector thread, the API) see a consistent snapshot via a volatile/atomic read
 * with no lock, mirroring {@code cf-trader.aggregator.Aggregator}'s "immutable value published by
 * a single reference/field swap" idiom.
 *
 * <p>This is also where the kill switch's floor is CHECKED (not enforced — {@link io.cfarb.risk.KillSwitch}
 * owns the enforcement/trip action); Portfolio only ever reports the current number honestly.
 */
public final class Portfolio {

    private final AtomicLong equityFixed;
    private final long seedFixed;
    private final AtomicLong realizedTradeCount = new AtomicLong();
    private final AtomicLong brokenCycleCount = new AtomicLong();

    public Portfolio(long seedFixed) {
        this.seedFixed = seedFixed;
        this.equityFixed = new AtomicLong(seedFixed);
    }

    public long equity() {
        return equityFixed.get();
    }

    public long seed() {
        return seedFixed;
    }

    /** Apply a completed (all 3 legs filled) cycle's realized PnL — may be negative (adverse
     * slippage between detection and execution). Called ONLY by the executor thread. */
    public long applyRealizedPnl(long pnlFixed) {
        realizedTradeCount.incrementAndGet();
        return equityFixed.addAndGet(pnlFixed);
    }

    /** Apply a broken cycle's net anchor-denominated PnL — {@code recovered anchor - anchor spent},
     * both sides of that subtraction already anchor-denominated (cf-arb-bot-review-plan.md Tier 1
     * step 1.4). NOT always non-positive: a leg can fail after the book moved favorably, or the
     * unwind can recover more than was spent. Tracked separately from clean trades so the journal
     * and /api/v1/state can distinguish "the strategy lost" from "execution broke." Renamed from
     * {@code applyBrokenCycleLoss} — that name's javadoc promised "always non-positive," which the
     * previous (currency-mismatched) arithmetic did not actually honor. */
    public long applyBrokenCyclePnl(long pnlFixed) {
        brokenCycleCount.incrementAndGet();
        return equityFixed.addAndGet(pnlFixed);
    }

    public long realizedTradeCount() {
        return realizedTradeCount.get();
    }

    public long brokenCycleCount() {
        return brokenCycleCount.get();
    }

    public double pnlPctOfSeed() {
        return (equityFixed.get() - seedFixed) / (double) seedFixed * 100.0;
    }
}

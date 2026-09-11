package io.cfarb.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.cfarb.model.Side;
import org.junit.jupiter.api.Test;

/**
 * PRE-LIVE-PLAN.md P0-1 ("Fix B"): {@link ConsumptionLedger} in isolation -- record/consume round
 * trip, expiry on a venue rewrite (writeSeq advances) or the TTL backstop, and {@link
 * ConsumptionLedger#clear}.
 */
class ConsumptionLedgerTest {

    @Test
    void recordThenConsumedRoundTrip() {
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        long t0 = 10_000_000_000L;

        ledger.record(1, Side.ASK, 0, 42L, 100L, t0);

        assertEquals(100L, ledger.consumed(1, Side.ASK, 0, 42L, t0 + 1_000_000L));
    }

    @Test
    void aSecondClaimAtTheSameWriteSeqAccumulates() {
        // Two fires each partially draining the same still-unrewritten level.
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        long t0 = 10_000_000_000L;

        ledger.record(1, Side.ASK, 0, 42L, 100L, t0);
        ledger.record(1, Side.ASK, 0, 42L, 30L, t0 + 1_000_000L);

        assertEquals(130L, ledger.consumed(1, Side.ASK, 0, 42L, t0 + 2_000_000L));
    }

    @Test
    void consumedIsZeroForAnUnrecordedSlot() {
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        assertEquals(0L, ledger.consumed(1, Side.ASK, 0, 1L, 10_000_000_000L));
    }

    @Test
    void entryDiesWhenTheLevelsWriteSeqAdvances() {
        // The venue rewrote the level -- genuinely new liquidity, the old claim must not apply.
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        long t0 = 10_000_000_000L;

        ledger.record(1, Side.ASK, 0, 42L, 100L, t0);
        assertEquals(0L, ledger.consumed(1, Side.ASK, 0, 43L, t0 + 1_000_000L),
                "a different (advanced) writeSeq must see no claim");
    }

    @Test
    void aClaimAtANewWriteSeqResetsRatherThanAccumulatesOntoTheOldOne() {
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        long t0 = 10_000_000_000L;

        ledger.record(1, Side.ASK, 0, 42L, 100L, t0);
        ledger.record(1, Side.ASK, 0, 43L, 25L, t0 + 1_000_000L); // level rewritten, new claim

        assertEquals(25L, ledger.consumed(1, Side.ASK, 0, 43L, t0 + 2_000_000L),
                "the new writeSeq's claim must not carry over the old (now-stale) 100");
    }

    @Test
    void entryDiesAfterTheTtlElapsesEvenAtTheSameWriteSeq() {
        long ttlMs = 5_000;
        ConsumptionLedger ledger = new ConsumptionLedger(4, ttlMs);
        long t0 = 10_000_000_000L;

        ledger.record(1, Side.ASK, 0, 42L, 100L, t0);

        long justInside = t0 + ttlMs * 1_000_000L - 1;
        assertEquals(100L, ledger.consumed(1, Side.ASK, 0, 42L, justInside), "still inside the TTL");

        long justOutside = t0 + ttlMs * 1_000_000L + 1;
        assertEquals(0L, ledger.consumed(1, Side.ASK, 0, 42L, justOutside),
                "past the TTL backstop, even though writeSeq never advanced");
    }

    @Test
    void bidAndAskSidesOfTheSameSymbolAreIndependent() {
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        long t0 = 10_000_000_000L;

        ledger.record(2, Side.ASK, 0, 1L, 50L, t0);
        ledger.record(2, Side.BID, 0, 1L, 75L, t0);

        assertEquals(50L, ledger.consumed(2, Side.ASK, 0, 1L, t0));
        assertEquals(75L, ledger.consumed(2, Side.BID, 0, 1L, t0));
    }

    @Test
    void differentSymbolsAreIndependent() {
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        long t0 = 10_000_000_000L;

        ledger.record(0, Side.ASK, 0, 1L, 50L, t0);
        ledger.record(3, Side.ASK, 0, 1L, 999L, t0);

        assertEquals(50L, ledger.consumed(0, Side.ASK, 0, 1L, t0));
        assertEquals(999L, ledger.consumed(3, Side.ASK, 0, 1L, t0));
    }

    @Test
    void differentLevelIndicesAreIndependent() {
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        long t0 = 10_000_000_000L;

        ledger.record(1, Side.ASK, 0, 1L, 10L, t0);
        ledger.record(1, Side.ASK, 1, 2L, 20L, t0);

        assertEquals(10L, ledger.consumed(1, Side.ASK, 0, 1L, t0));
        assertEquals(20L, ledger.consumed(1, Side.ASK, 1, 2L, t0));
    }

    @Test
    void clearWipesEveryLevelOnOneSideOfOneSymbol() {
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        long t0 = 10_000_000_000L;
        ledger.record(1, Side.ASK, 0, 1L, 10L, t0);
        ledger.record(1, Side.ASK, 5, 1L, 20L, t0);
        ledger.record(1, Side.BID, 0, 1L, 30L, t0); // different side -- must survive the clear

        ledger.clear(1, Side.ASK);

        assertEquals(0L, ledger.consumed(1, Side.ASK, 0, 1L, t0));
        assertEquals(0L, ledger.consumed(1, Side.ASK, 5, 1L, t0));
        assertEquals(30L, ledger.consumed(1, Side.BID, 0, 1L, t0), "the untouched side must be unaffected");
    }

    @Test
    void levelIndexAtOrBeyondMaxTrackedLevelsIsSilentlyIgnored() {
        // Conservative: deeper walks simply go unmodelled, never an array-bounds error.
        ConsumptionLedger ledger = new ConsumptionLedger(4, 5_000);
        long t0 = 10_000_000_000L;

        ledger.record(1, Side.ASK, Sizer.MAX_TRACKED_LEVELS, 1L, 100L, t0); // out of range
        ledger.record(1, Side.ASK, Sizer.MAX_TRACKED_LEVELS - 1, 1L, 50L, t0); // in range, deepest slot

        assertEquals(0L, ledger.consumed(1, Side.ASK, Sizer.MAX_TRACKED_LEVELS, 1L, t0));
        assertEquals(50L, ledger.consumed(1, Side.ASK, Sizer.MAX_TRACKED_LEVELS - 1, 1L, t0));
    }

    @Test
    void nonPositiveTtlRefusesToBoot() {
        assertThrows(IllegalStateException.class, () -> new ConsumptionLedger(4, 0));
        assertThrows(IllegalStateException.class, () -> new ConsumptionLedger(4, -1));
    }
}

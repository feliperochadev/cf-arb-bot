package io.cfarb.model;

/**
 * Single-entry venue enum, ported from {@code cf-trader.model.Exchange}. Kept as an enum rather
 * than deleted (cf-arb-bot-plan.md §4): the config {@code Map<String,...>} shape, per-symbol fee
 * arrays, and any future second venue all hang off this dimension staying present. Ordinals are
 * load-bearing array indices on the hot path — append only, never reorder existing entries.
 */
public enum Exchange {
    MEXC("mexc");

    public static final Exchange[] VALUES = values();
    public static final int COUNT = VALUES.length;

    private final String id;

    Exchange(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}

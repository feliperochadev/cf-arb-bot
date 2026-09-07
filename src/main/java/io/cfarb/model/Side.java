package io.cfarb.model;

/**
 * A trade leg's side, matching {@code cfarb.stage2_cycles.TradeLeg.side} in the Python research
 * pipeline exactly ("bid" = sell base into the bid, "ask" = buy base by lifting the ask) so the
 * two models stay provably comparable (cf-arb-bot-plan.md §11 verification item 3).
 */
public enum Side {
    /** Sell base for quote — hit the bid. rate = bid_px (quote received per unit base). */
    BID,
    /** Buy base with quote — lift the ask. rate = 1 / ask_px (base received per unit quote). */
    ASK
}

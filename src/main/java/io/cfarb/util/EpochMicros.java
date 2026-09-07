package io.cfarb.util;

import java.time.Instant;

/** Ported verbatim from {@code recorder-service.util.EpochMicros}. True microsecond wall-clock
 * stamp for journal entries and cross-leg skew measurement against MEXC's protobuf {@code sendTime}
 * field — never used for hot-path gating/cooldown (that is always {@code System.nanoTime()}, per
 * hot-path rule R12 / security rule S14). */
public final class EpochMicros {

    private EpochMicros() {
    }

    public static long now() {
        Instant instant = Instant.now();
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }
}

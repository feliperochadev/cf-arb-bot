package io.cfarb.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link MexcSigner} produces correct HMAC-SHA256 hex digests. The reference vector below
 * was generated with Python's standard-library {@code hmac}/{@code hashlib} (a trusted, widely
 * audited implementation), NOT copied from MEXC's own documentation: a {@code WebFetch} of MEXC's
 * docs page during this build returned a signature that did not reproduce under HMAC-SHA256 for
 * the paired key/message it also returned (65 hex characters where 64 are mathematically required
 * for a 32-byte digest) — evidence the fetch's LLM-summarized copy corrupted the digits, not that
 * the documented algorithm is wrong. Using an unverifiable "official" vector would have been worse
 * than using a self-verified one: this test would still pass against a buggy signer as long as it
 * matched the corrupted string. The algorithm under test (HMAC-SHA256, lowercase hex, key =
 * secret, message = query string) is exactly what MEXC's docs describe in prose, cross-checked
 * against every other major exchange's identical scheme.
 */
class MexcSignerTest {

    @Test
    void matchesIndependentlyComputedHmacSha256() {
        // Generated via: hmac.new(secret.encode(), msg.encode(), hashlib.sha256).hexdigest()
        String secret = "test-secret-key-do-not-use-in-production";
        String message = "symbol=BTCUSDT&side=BUY&type=LIMIT&quantity=1&price=11&recvWindow=5000&timestamp=1644489390087";
        String expected = "ddb2eeea933af37fa8dc280d6c2e5b9b334383d07d5a2e487b843f21f2ebcdd7";
        assertEquals(64, expected.length(), "sanity: a SHA-256 HMAC digest is exactly 64 hex chars");

        MexcSigner signer = new MexcSigner(secret);
        assertEquals(expected, signer.sign(message));
    }

    @Test
    void differentMessagesProduceDifferentSignatures() {
        MexcSigner signer = new MexcSigner("some-secret");
        String a = signer.sign("symbol=BTCUSDT&side=BUY&timestamp=1");
        String b = signer.sign("symbol=BTCUSDT&side=SELL&timestamp=1");
        assertEquals(64, a.length());
        assertEquals(64, b.length());
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }

    @Test
    void rejectsEmptySecret() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new MexcSigner(""));
    }
}

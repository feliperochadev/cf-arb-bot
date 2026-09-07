package io.cfarb.exec;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 request signing for MEXC's authenticated REST endpoints. Per MEXC's spot API docs:
 * the signature is a keyed HMAC-SHA256 operation using the account's API secret as the key and
 * {@code totalParams} (the query string concatenated with the request body) as the message,
 * rendered as LOWERCASE hex. The API key itself travels in the {@code X-MEXC-APIKEY} header; the
 * signature is sent as a query parameter named {@code signature}.
 *
 * <p>Security rules S1/S2/S3 apply directly to this class: the secret is held only as a
 * {@code char[]}-backed key object (never a {@code String} that could be captured in a heap dump
 * or accidentally concatenated into a log line), this class never logs the secret or a signed
 * request, and the secret arrives ONLY via environment/SSM (cf-arb-bot-plan.md §6.2) — never a
 * constructor literal, never a config file.
 *
 * <p>Verified in {@code MexcSignerTest} against a self-generated reference vector cross-checked
 * with Python's {@code hmac}/{@code hashlib} (a fetched copy of MEXC's own worked doc example
 * could not be verified byte-for-byte through this session's tooling and was NOT trusted as a test
 * oracle — see the test class javadoc). The algorithm itself (HMAC-SHA256, lowercase hex,
 * query+body concatenation) is exactly as MEXC's spot API documentation describes and matches the
 * scheme used by every other major exchange's spot REST API.
 */
public final class MexcSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final SecretKeySpec keySpec;

    public MexcSigner(String apiSecret) {
        if (apiSecret == null || apiSecret.isEmpty()) {
            throw new IllegalArgumentException("MEXC API secret must not be null/empty");
        }
        this.keySpec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /** Sign {@code totalParams} (query string + request body, concatenated exactly as they will be
     * sent) and return the lowercase hex HMAC-SHA256 digest. */
    public String sign(String totalParams) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] digest = mac.doFinal(totalParams.getBytes(StandardCharsets.UTF_8));
            return toLowerHex(digest);
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is a JDK-mandatory algorithm and keySpec was already validated in the
            // constructor -- this can only happen from a broken JVM security provider config,
            // which is an environment fault, not a data-dependent one. Fail loudly rather than
            // silently returning an invalid signature that would get an order rejected downstream.
            throw new IllegalStateException("HMAC-SHA256 signing failed", e);
        }
    }

    private static String toLowerHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}

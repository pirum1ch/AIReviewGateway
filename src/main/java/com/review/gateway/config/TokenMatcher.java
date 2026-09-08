package com.review.gateway.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;

/**
 * SR-02's constant-time bearer-token comparison, extracted out of {@link TokenAuthenticationFilter} so
 * {@link GitLabWebhookSecretFilter} (WHR-01) can reuse it exactly rather than forking a second
 * implementation. Both sides are first SHA-256-hashed to a fixed-length digest, then compared with
 * {@link MessageDigest#isEqual(byte[], byte[])} (guaranteed by the JDK to take time independent of where
 * a mismatch occurs).
 */
final class TokenMatcher {

    private TokenMatcher() {
    }

    static boolean constantTimeEquals(String presented, String configured) {
        if (configured == null || configured.isBlank() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(presented), sha256(configured));
    }

    /**
     * WHR-01/WHR-04: matches {@code presented} against a <em>set</em> of valid secrets (rotation
     * support) — every entry is checked (no early-exit optimization beyond the loop itself), consistent
     * with {@link TokenAuthenticationFilter#matchRole} checking all three roles unconditionally.
     */
    static boolean matchesAny(String presented, Collection<String> configuredSecrets) {
        if (presented == null || configuredSecrets == null) {
            return false;
        }
        boolean matched = false;
        for (String candidate : configuredSecrets) {
            if (constantTimeEquals(presented, candidate)) {
                matched = true;
            }
        }
        return matched;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm (JCA standard names); this can never actually happen.
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
        }
    }
}

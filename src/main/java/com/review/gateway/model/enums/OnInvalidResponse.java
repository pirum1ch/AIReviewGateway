package com.review.gateway.model.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * {@code gateway.structured.on-invalid-response} (architecture SRO-38, threat model SOR-05/SRO-68,
 * BLOCKING-derived correction). {@code RETRY_THEN_FAIL} is the default and the recommended steady
 * state; {@code RETRY_THEN_FALLBACK} is the documented rollout/emergency escape hatch, strictly
 * restricted by SRO-68 so it can never publish an unvalidated model transcript.
 *
 * <p>{@link #fromNullable(String)} — never {@link #valueOf(String)} on raw config text — same
 * defensive-parse precedent as {@code StructuredOutputMode}/{@code PromptMessageFormat}: a typo in
 * config degrades to the safe default ({@code RETRY_THEN_FAIL}) with a WARN, never crashes startup or
 * (worse) silently behaves as the more permissive mode.
 */
public enum OnInvalidResponse {
    RETRY_THEN_FAIL,
    RETRY_THEN_FALLBACK;

    public static Optional<OnInvalidResponse> fromNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (OnInvalidResponse value : values()) {
            if (value.name().equals(normalized)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}

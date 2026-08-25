package com.review.gateway.model.enums;

import java.util.Locale;

/**
 * Closed set of llama-server {@code finish_reason} values the Gateway will actually classify (SRO-42/
 * 43): {@code stop} (normal completion), {@code length} (max-tokens exhausted — the signal {@code
 * StructuredResponseParser}'s {@code TRUNCATED} classification keys on), {@code content_filter},
 * {@code tool_calls}. Anything else — an old/new backend build, a typo, {@code null} — maps to {@link
 * #UNKNOWN}, logged at DEBUG, never a {@code 400} and never {@link #valueOf(String)} on caller-supplied
 * text (same forward-compatibility rule as {@code JobFailureReason}/{@code promptMessageFormat}).
 */
public enum FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    TOOL_CALLS,
    /** Not a wire value — the safe fallback for {@code null}/unrecognized input. */
    UNKNOWN;

    /** @return the parsed value, whitelist-matched against the wire vocabulary; never throws. */
    public static FinishReason fromWireValue(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "stop" -> STOP;
            case "length" -> LENGTH;
            case "content_filter" -> CONTENT_FILTER;
            case "tool_calls" -> TOOL_CALLS;
            default -> UNKNOWN;
        };
    }

    /** The exact lowercase wire-shaped text persisted to {@code review_results.finish_reason}. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}

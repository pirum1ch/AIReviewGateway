package com.review.gateway.dto;

import java.util.List;

/**
 * Nested payload of {@link ClaimJobResponse} (architecture §11), built from {@code review_chunks}.
 * {@code chunkContext} (V2, diff chunking) is the rendered cross-chunk header (§3), {@code null} for a
 * single-chunk Review.
 *
 * <p>Prompt Manager (V3): {@code systemMessages} is {@code null} for a legacy/kill-switch-off Review
 * (the Worker falls back to its own template {@code system:} block — this is the explicit, tested
 * "null means legacy" branch, PMR-24, not a fallback-on-error) or a non-empty list of pre-rendered
 * system-role messages (MULTI: one per section; SINGLE: exactly one, joined) otherwise.
 */
public record JobPayload(String diff, String promptVersion, String chunkContext, List<String> systemMessages) {

    /**
     * CSR-14/PMR-25: the default record {@code toString()} would dump the full (proprietary) diff,
     * chunk-context, and now system-prompt text into any accidental {@code log.debug("{}",
     * payload)}/exception-message rendering. Mirrors the Worker's existing {@code
     * gateway.dto.JobPayload#toString()} pattern. Does not affect JSON (de)serialization, which Jackson
     * performs via the accessors/canonical constructor, not {@code toString()}.
     */
    @Override
    public String toString() {
        int diffChars = diff == null ? 0 : diff.length();
        int contextChars = chunkContext == null ? 0 : chunkContext.length();
        return "JobPayload[diff=<masked, " + diffChars + " chars>, promptVersion=" + promptVersion
                + ", chunkContext=<masked, " + contextChars + " chars>, systemMessages=" + maskSystemMessages() + "]";
    }

    private String maskSystemMessages() {
        if (systemMessages == null) {
            return "null";
        }
        int totalChars = systemMessages.stream().mapToInt(m -> m == null ? 0 : m.length()).sum();
        return "<masked, " + systemMessages.size() + " msg, " + totalChars + " chars>";
    }
}

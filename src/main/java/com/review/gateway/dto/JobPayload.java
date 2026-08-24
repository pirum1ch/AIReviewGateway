package com.review.gateway.dto;

import java.util.List;

/**
 * Nested payload of {@link ClaimJobResponse} (architecture §11), built from {@code review_chunks}.
 * {@code chunkContext} (V2, diff chunking) is the rendered cross-chunk header (§3), {@code null} for a
 * single-chunk Review — <b>except</b> for a structured (v3) job, where it is never {@code null}
 * regardless of chunk count (SRO-64a).
 *
 * <p>Prompt Manager (V3): {@code systemMessages} is {@code null} for a legacy/kill-switch-off Review
 * (the Worker falls back to its own template {@code system:} block — this is the explicit, tested
 * "null means legacy" branch, PMR-24, not a fallback-on-error) or a non-empty list of pre-rendered
 * system-role messages (MULTI: one per section; SINGLE: exactly one, joined) otherwise.
 *
 * <p>Structured Review Output (V5, SRO-10): {@code responseFormat}/{@code jsonSchema} are the Gateway-
 * computed decoder constraint (§3.2/§3.3), at most one of the two ever non-null (SRO-13) — the Worker
 * attaches whichever is set verbatim to its outbound {@code /v1/chat/completions} call and never
 * inspects/derives either. Both {@code null} means "no constraint for this job" (backend {@code OFF},
 * kill switch, or a non-structured prompt version) — byte-identical to today's request shape.
 */
public record JobPayload(String diff, String promptVersion, String chunkContext, List<String> systemMessages,
                          String responseFormat, String jsonSchema) {

    /**
     * CSR-14/PMR-25/SRO-10: the default record {@code toString()} would dump the full (proprietary)
     * diff, chunk-context, system-prompt text, and now the decoder-constraint schema (which embeds
     * MR-author-controlled file paths) into any accidental {@code log.debug("{}",
     * payload)}/exception-message rendering. Mirrors the Worker's existing {@code
     * gateway.dto.JobPayload#toString()} pattern. Does not affect JSON (de)serialization, which Jackson
     * performs via the accessors/canonical constructor, not {@code toString()}.
     */
    @Override
    public String toString() {
        int diffChars = diff == null ? 0 : diff.length();
        int contextChars = chunkContext == null ? 0 : chunkContext.length();
        return "JobPayload[diff=<masked, " + diffChars + " chars>, promptVersion=" + promptVersion
                + ", chunkContext=<masked, " + contextChars + " chars>, systemMessages=" + maskSystemMessages()
                + ", responseFormat=" + maskNullable(responseFormat) + ", jsonSchema=" + maskNullable(jsonSchema) + "]";
    }

    private String maskSystemMessages() {
        if (systemMessages == null) {
            return "null";
        }
        int totalChars = systemMessages.stream().mapToInt(m -> m == null ? 0 : m.length()).sum();
        return "<masked, " + systemMessages.size() + " msg, " + totalChars + " chars>";
    }

    private String maskNullable(String value) {
        return value == null ? "null" : "<masked, " + value.length() + " chars>";
    }
}

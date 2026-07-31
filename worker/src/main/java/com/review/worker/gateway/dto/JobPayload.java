package com.review.worker.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Mirrors the Gateway's {@code com.review.gateway.dto.JobPayload} field-for-field. {@code
 * chunkContext} (V2, diff chunking) is the rendered cross-chunk header, {@code null} for a
 * single-chunk Review — the Worker stays "chunk-ignorant" otherwise: it just substitutes this text
 * into {@code {{CHUNK_CONTEXT}}} if the resolved template has that placeholder, exactly like {@code
 * diff}/{@code {{DIFF}}}.
 *
 * <p>Prompt Manager (V3): {@code systemMessages} — when present — becomes {@code ChatMessage("system",
 * ...)} entries verbatim (PMR-23), one per element, in order; {@code null} is the explicit legacy/
 * kill-switch-off branch (PMR-24), not an error fallback. {@code @JsonIgnoreProperties(ignoreUnknown =
 * true)} (PMT-05/PMR-24): an old Worker talking to a newer Gateway that sends fields this DTO doesn't
 * know about yet must not fail deserialization — forward compatibility is a stated contract here, not
 * an accident of Jackson defaults (the llama DTOs already have this annotation; this one and {@link
 * ClaimResponse} previously did not).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobPayload(String diff, String promptVersion, String chunkContext, List<String> systemMessages) {

    /**
     * FW-05/WSR-10/PMR-25 hardening: the default record {@code toString()} would dump the full
     * (proprietary) diff, chunk-context, and now system-prompt text into any accidental {@code
     * log.debug("{}", job)}/exception-message rendering. This does not affect JSON (de)serialization,
     * which Jackson performs via the accessors/canonical constructor, not {@code toString()}.
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

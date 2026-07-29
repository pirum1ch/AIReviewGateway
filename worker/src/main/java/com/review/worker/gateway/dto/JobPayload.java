package com.review.worker.gateway.dto;

/**
 * Mirrors the Gateway's {@code com.review.gateway.dto.JobPayload} field-for-field. {@code
 * chunkContext} (V2, diff chunking) is the rendered cross-chunk header, {@code null} for a
 * single-chunk Review — the Worker stays "chunk-ignorant" otherwise: it just substitutes this text
 * into {@code {{CHUNK_CONTEXT}}} if the resolved template has that placeholder, exactly like {@code
 * diff}/{@code {{DIFF}}}.
 */
public record JobPayload(String diff, String promptVersion, String chunkContext) {

    /**
     * FW-05/WSR-10 hardening: the default record {@code toString()} would dump the full (proprietary)
     * diff and chunk-context text into any accidental {@code log.debug("{}", job)}/exception-message
     * rendering. This does not affect JSON (de)serialization, which Jackson performs via the
     * accessors/canonical constructor, not {@code toString()}.
     */
    @Override
    public String toString() {
        int diffChars = diff == null ? 0 : diff.length();
        int contextChars = chunkContext == null ? 0 : chunkContext.length();
        return "JobPayload[diff=<masked, " + diffChars + " chars>, promptVersion=" + promptVersion
                + ", chunkContext=<masked, " + contextChars + " chars>]";
    }
}

package com.review.gateway.dto;

/**
 * Nested payload of {@link ClaimJobResponse} (architecture §11), built from {@code review_chunks}.
 * {@code chunkContext} (V2, diff chunking) is the rendered cross-chunk header (§3), {@code null} for a
 * single-chunk Review.
 */
public record JobPayload(String diff, String promptVersion, String chunkContext) {

    /**
     * CSR-14: the default record {@code toString()} would dump the full (proprietary) diff and
     * chunk-context text into any accidental {@code log.debug("{}", payload)}/exception-message
     * rendering. Mirrors the Worker's existing {@code gateway.dto.JobPayload#toString()} pattern. Does
     * not affect JSON (de)serialization, which Jackson performs via the accessors/canonical
     * constructor, not {@code toString()}.
     */
    @Override
    public String toString() {
        int diffChars = diff == null ? 0 : diff.length();
        int contextChars = chunkContext == null ? 0 : chunkContext.length();
        return "JobPayload[diff=<masked, " + diffChars + " chars>, promptVersion=" + promptVersion
                + ", chunkContext=<masked, " + contextChars + " chars>]";
    }
}

package com.review.gateway.service.dto;

/**
 * Successful {@code POST /jobs/claim} outcome (architecture §11 {@code ClaimJobResponse} +
 * {@code JobPayload}, flattened). {@code diff} is this chunk's slice (V2, diff chunking — built from
 * {@code review_chunks}, not the whole {@code review_inputs.diff}). {@code chunkContext} is the
 * rendered cross-chunk header (§3), or {@code null} when the Review has only one chunk.
 */
public record ClaimedJob(Long jobId, Long reviewId, String diff, String promptVersion, String chunkContext) {

    /**
     * CSR-14: mirrors {@code dto.JobPayload#toString()} — never dump the diff/chunk-context content
     * into an accidental log/exception-message rendering.
     */
    @Override
    public String toString() {
        int diffChars = diff == null ? 0 : diff.length();
        int contextChars = chunkContext == null ? 0 : chunkContext.length();
        return "ClaimedJob[jobId=" + jobId + ", reviewId=" + reviewId + ", diff=<masked, " + diffChars
                + " chars>, promptVersion=" + promptVersion + ", chunkContext=<masked, " + contextChars + " chars>]";
    }
}

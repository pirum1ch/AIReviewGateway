package com.review.gateway.service.dto;

import java.util.List;

/**
 * Successful {@code POST /jobs/claim} outcome (architecture §11 {@code ClaimJobResponse} +
 * {@code JobPayload}, flattened). {@code diff} is this chunk's slice (V2, diff chunking — built from
 * {@code review_chunks}, not the whole {@code review_inputs.diff}). {@code chunkContext} is the
 * rendered cross-chunk header (§3), or {@code null} when the Review has only one chunk.
 *
 * <p>Prompt Manager (V3): {@code systemMessages} is {@code null} for a
 * {@link com.review.gateway.model.enums.PromptBundleMode#NONE} Review (legacy behavior — the Worker
 * falls back to its own template {@code system:} block) and a non-empty list for a {@code REPO} Review
 * (PMR-09 guarantees at least the two mandatory corporate sections whenever this reaches here — see
 * {@code PromptMessageFormatter}/{@code QueueManager.claimJobRow}'s fail-closed handling).
 */
public record ClaimedJob(Long jobId, Long reviewId, String diff, String promptVersion, String chunkContext,
                          List<String> systemMessages) {

    /**
     * CSR-14/PMR-25: mirrors {@code dto.JobPayload#toString()} — never dump the diff/chunk-context/
     * system-message content into an accidental log/exception-message rendering.
     */
    @Override
    public String toString() {
        int diffChars = diff == null ? 0 : diff.length();
        int contextChars = chunkContext == null ? 0 : chunkContext.length();
        return "ClaimedJob[jobId=" + jobId + ", reviewId=" + reviewId + ", diff=<masked, " + diffChars
                + " chars>, promptVersion=" + promptVersion + ", chunkContext=<masked, " + contextChars
                + " chars>, systemMessages=" + maskSystemMessages() + "]";
    }

    private String maskSystemMessages() {
        if (systemMessages == null) {
            return "null";
        }
        int totalChars = systemMessages.stream().mapToInt(m -> m == null ? 0 : m.length()).sum();
        return "<masked, " + systemMessages.size() + " msg, " + totalChars + " chars>";
    }
}

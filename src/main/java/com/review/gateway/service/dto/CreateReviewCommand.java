package com.review.gateway.service.dto;

/**
 * Input to {@code ReviewService#createReview}. Mirrors the eventual {@code CreateReviewRequest}
 * controller DTO (architecture §11) but lives in {@code service.dto} since no controller exists yet.
 */
public record CreateReviewCommand(
        Long projectId,
        Long mergeRequestId,
        String headSha,
        String baseSha,
        String diff,
        String promptVersion,
        Integer priority) {

    /**
     * F-DC-07: masked {@code toString()} — {@code diff} is the (possibly proprietary) full MR diff and
     * must never be dumped whole into a log/exception-message rendering. Latent (no current call site
     * logs this record), fixed while applying the same CSR-14 pattern to the other content-carrying
     * DTOs in this area.
     */
    @Override
    public String toString() {
        int diffChars = diff == null ? 0 : diff.length();
        return "CreateReviewCommand[projectId=" + projectId + ", mergeRequestId=" + mergeRequestId
                + ", headSha=" + headSha + ", baseSha=" + baseSha + ", diff=<masked, " + diffChars
                + " chars>, promptVersion=" + promptVersion + ", priority=" + priority + "]";
    }
}

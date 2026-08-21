package com.review.gateway.service.dto;

/**
 * The three commit SHAs GitLab associates with a Merge Request's current diff ({@code diff_refs} in the
 * GitLab API) — {@code base_sha}/{@code start_sha}/{@code head_sha}. Returned by
 * {@code GitLabClient#fetchDiffRefs} only when all three are present and each matches
 * {@code ^[0-9a-f]{40}$} (DPR-07); never partially populated.
 */
public record DiffRefs(String baseSha, String startSha, String headSha) {

    /** DPR-15 (SHOULD): SHAs are still identifying material — never dump them whole into a log/exception rendering. */
    @Override
    public String toString() {
        return "DiffRefs[baseSha=" + maskSha(baseSha) + ", startSha=" + maskSha(startSha)
                + ", headSha=" + maskSha(headSha) + "]";
    }

    private static String maskSha(String sha) {
        if (sha == null) {
            return "null";
        }
        return sha.length() >= 7 ? sha.substring(0, 7) + "..." : "***";
    }
}

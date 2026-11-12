package com.review.gateway.service.dto;

/**
 * A Gateway-computed GitLab discussion position, ready to be wired onto {@code POST .../discussions}
 * (Diff Position Anchoring). Combines a fetched {@link DiffRefs} with one
 * {@code DiffPositionResolver.ResolvedLine} for a specific comment. {@code oldPath}/{@code newPath} are
 * never {@code /dev/null}; {@code oldLine} is {@code null} for an added line (DPR-03). Nullable end to
 * end: a {@code null} {@code DiffPosition} means "publish as a plain note" (today's behavior).
 */
public record DiffPosition(String baseSha, String startSha, String headSha,
                            String oldPath, String newPath, Integer oldLine, Integer newLine) {

    /** DPR-15 (SHOULD): paths/SHAs are still identifying material — never dump them whole into a log/exception rendering. */
    @Override
    public String toString() {
        int oldPathChars = oldPath == null ? 0 : oldPath.length();
        int newPathChars = newPath == null ? 0 : newPath.length();
        return "DiffPosition[baseSha=" + maskSha(baseSha) + ", startSha=" + maskSha(startSha)
                + ", headSha=" + maskSha(headSha) + ", oldPath=<masked, " + oldPathChars
                + " chars>, newPath=<masked, " + newPathChars + " chars>, oldLine=" + oldLine
                + ", newLine=" + newLine + "]";
    }

    private static String maskSha(String sha) {
        if (sha == null) {
            return "null";
        }
        return sha.length() >= 7 ? sha.substring(0, 7) + "..." : "***";
    }
}

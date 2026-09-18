package com.review.gateway.service;

import com.review.gateway.exception.DiffFetchUnavailableException;
import com.review.gateway.exception.DiffIntegrityException;
import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.exception.PromptSourceInvalidException;
import com.review.gateway.exception.PromptSourceUnavailableException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Boundary interface to the GitLab API (architecture §11, §7). The real implementation
 * (a {@code RestClient}-backed bean, {@code gateway.gitlab.*} config) arrives in
 * feature/03-api-security; this stage only defines the contract {@link GitLabPublisher} programs
 * against, so it can be unit-tested against a mock now.
 *
 * <p>Prompt Manager (V3, architecture §7) adds three new read-only calls, all served by the dedicated
 * {@code gitLabPromptRestClient} bean (separate token/timeouts from the write-scoped
 * {@code gitLabRestClient} used by {@link #postDiscussion} — PMR-15).
 */
public interface GitLabClient {

    /**
     * Posts a new discussion (single comment) on the given Merge Request.
     *
     * @return the GitLab-assigned discussion id, to be stored for idempotent re-publish tracking
     * @throws GitLabPublishException on any transient failure (network error, non-2xx, timeout) —
     *         {@link GitLabPublisher} treats this as "retry later", never as fatal to the Review.
     */
    String postDiscussion(Long projectId, Long mergeRequestId, String body);

    /**
     * Resolves {@code ref} (a branch/tag name) to its current commit SHA on {@code projectRef} (a
     * numeric project id or {@code group/project}-style path, as a string — never a URL). Always run
     * <em>before</em> {@link #fetchRawFile} for the same source (architecture §3): (a) it gives a
     * consistent snapshot across multiple file fetches of the same ref, and (b) GitLab returns 404
     * identically for "file not found" and "project/ref not accessible" — resolving the SHA first
     * proves access, so a later 404 on a specific file is unambiguous.
     *
     * @throws PromptSourceUnavailableException on any non-2xx response (including 404 — PMR-26: this
     *         call never distinguishes "not found" from "no access")
     */
    String resolveCommitSha(String projectRef, String ref);

    /**
     * Fetches one file's raw content at the given (already-resolved) commit SHA, streaming the read
     * bounded at {@code maxBytes + 1} (PMR-17 — never buffers first and checks after).
     *
     * @return the file's content, decoded as UTF-8, or {@link Optional#empty()} on a bare 404 (the
     *         project/ref must already have been proven reachable by a prior {@link #resolveCommitSha}
     *         call for this to be interpreted as "file genuinely absent" rather than "no access")
     * @throws PromptSourceUnavailableException on any other non-2xx response or network failure
     * @throws PromptSourceInvalidException if the file exceeds {@code maxBytes}, is not valid UTF-8,
     *         contains a NUL byte, or is empty
     */
    Optional<String> fetchRawFile(String projectRef, String filePath, String commitSha, int maxBytes);

    /**
     * Resolves {@code projectRef}'s default branch name.
     *
     * @throws PromptSourceUnavailableException on any non-2xx response (including 404)
     */
    String resolveDefaultBranch(String projectRef);

    // ---- GitLab Webhook Diff Trigger (read-only, gitLabDiffRestClient, gateway.gitlab.diffToken) ----

    /**
     * Authoritative read of one Merge Request (threat model WHR-03): the reviewer set, both SHAs, and
     * MR state used to decide/act are read fresh from THIS response — never trusted from a webhook
     * payload.
     *
     * @throws DiffFetchUnavailableException on any transient failure (network, timeout, 5xx, unexpected
     *         non-2xx including 404 — PMR-26-style, coarse and undifferentiated)
     */
    MergeRequestSnapshot fetchMergeRequest(Long projectId, Long mergeRequestIid);

    /**
     * {@code GET /repository/compare?from=&to=&unidiff=true}, per-file JSON (WHR-06: SHAs bound via
     * {@code UriBuilder.queryParam}, never concatenation, and pinned to {@code ^[0-9a-f]{7,64}$} first).
     * Read through a bounded stream (WHR-19), never buffered-then-checked.
     *
     * @throws DiffFetchUnavailableException on a transient failure or an oversized response
     * @throws DiffIntegrityException with {@link DiffIntegrityException.Reason#DIFF_UNAVAILABLE} if the
     *         SHA pair is unreachable (404 — WHR-20, a force-push race, deterministic/non-retryable)
     */
    CompareResult compareDiff(Long projectId, String baseSha, String headSha);

    /**
     * The deprecated {@code GET /merge_requests/:iid/changes}, read only for its whole-MR
     * {@code overflow} boolean (WHR-16). A 404 here gets its own distinct log message and is treated as
     * integrity-unverifiable, not a generic network blip.
     *
     * @throws DiffFetchUnavailableException on a transient failure or an oversized response
     * @throws DiffIntegrityException with {@link DiffIntegrityException.Reason#DIFF_UNAVAILABLE} on 404
     */
    boolean fetchOverflowFlag(Long projectId, Long mergeRequestIid);

    /**
     * WHR-15b: {@code HEAD /repository/files/:path?ref=:ref}, returning the {@code X-Gitlab-Size}
     * response header with zero body cost — distinguishes a genuinely-empty new/deleted file from one
     * GitLab silently truncated to an empty {@code diff}. Any failure to verify is treated fail-closed.
     *
     * @throws DiffIntegrityException with {@link DiffIntegrityException.Reason#DIFF_TOO_LARGE_OR_TRUNCATED}
     *         if the size cannot be verified (any non-2xx, including 404, or a missing header)
     */
    long headFileSize(Long projectId, String filePath, String ref);

    /**
     * Best-effort anti-duplicate read of recent MR notes (WHT-20/WHR-28/F-WH-03): NEVER throws — any
     * failure (network, oversized response, parse error, page-cap exceeded) returns {@link
     * Optional#empty()} rather than an empty list, so a genuinely-successful read that simply found no
     * notes cannot be confused with a failed/ambiguous one. The caller's "if the read fails or is
     * ambiguous, choose do not post" rule (§4.4) must act on that distinction, not on list emptiness.
     * Paginated read follows {@code X-Next-Page} up to {@code gateway.gitlab.diff.max-pages}.
     */
    Optional<List<Note>> listRecentNotes(Long projectId, Long mergeRequestIid);

    /**
     * {@code GET /merge_requests?reviewer_username=&state=opened&scope=all&updated_after=} for
     * {@code ReviewerSweepService} (WHR-23): paginated, bounded by both {@code maxPages} and
     * {@code maxResults}.
     *
     * @throws DiffFetchUnavailableException on a transient failure or an oversized response
     */
    List<MergeRequestRef> listOpenMergeRequestsForReviewer(String reviewerUsername, Instant updatedAfter,
                                                            int maxPages, int maxResults);

    /** Authoritative MR snapshot (WHR-03/WHR-20): {@code reviewerIds} are numeric GitLab user ids only. */
    record MergeRequestSnapshot(String state, String baseSha, String headSha, List<Long> reviewerIds) {
    }

    /** {@code compareTimeout} is GitLab's own whole-response truncation signal (WHR-16). */
    record CompareResult(boolean compareTimeout, List<DiffEntry> files) {
    }

    /**
     * One per-file entry as GitLab's {@code /compare}/{@code /changes} JSON actually shapes it
     * (empirically confirmed field list, threat model plan §"Поправка после эмпирической проверки"):
     * {@code a_mode, b_mode, deleted_file, diff, new_file, new_path, old_path, renamed_file} — no
     * {@code additions}/{@code deletions}/{@code binary} fields exist on this GitLab version.
     */
    record DiffEntry(String oldPath, String newPath, String aMode, String bMode,
                      boolean newFile, boolean deletedFile, boolean renamedFile, String diff) {
    }

    /** One MR note/comment, for the anti-duplicate lookup (WHR-28: filtered by {@code authorId} server-side). */
    record Note(Long authorId, String body) {
    }

    /** One MR reference from the reviewer-sweep listing; {@code sha} is an UNTRUSTED hint only (WHR-05's pattern). */
    record MergeRequestRef(Long projectId, Long iid, String sha) {
    }
}

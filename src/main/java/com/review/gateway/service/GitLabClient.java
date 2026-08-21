package com.review.gateway.service;

import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.exception.PromptSourceInvalidException;
import com.review.gateway.exception.PromptSourceUnavailableException;
import com.review.gateway.service.dto.DiffPosition;
import com.review.gateway.service.dto.DiffRefs;

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
     * Posts a new discussion (single comment) on the given Merge Request. {@code position}, when
     * non-{@code null}, anchors the comment to a specific diff line (Diff Position Anchoring) so it
     * renders as a native diff thread rather than a top-level note; {@code null} preserves today's
     * plain-note behavior exactly. On an HTTP 400 with a non-{@code null} {@code position} attached, this
     * retries exactly once with the position omitted (DPR-08) — every other status (401/403/404/429/5xx,
     * network failure) keeps the existing transient-failure path unchanged.
     *
     * @return the GitLab-assigned discussion id, to be stored for idempotent re-publish tracking
     * @throws GitLabPublishException on any transient failure (network error, non-2xx after the 400
     *         retry rule above, timeout) — {@link GitLabPublisher} treats this as "retry later", never as
     *         fatal to the Review.
     */
    String postDiscussion(Long projectId, Long mergeRequestId, String body, DiffPosition position);

    /**
     * Resolves the three commit SHAs GitLab currently associates with {@code mrIid}'s diff
     * ({@code diff_refs.base_sha}/{@code start_sha}/{@code head_sha}) — Diff Position Anchoring. Binds
     * only those three fields (never free-text MR fields such as title/description/labels, DPR-07) and
     * never throws: any failure (network, non-2xx, a stale/import-state MR whose {@code diff_refs} is
     * {@code null}, a missing/malformed member) yields {@link Optional#empty()}, never a
     * partially-populated {@code DiffRefs}.
     */
    Optional<DiffRefs> fetchDiffRefs(Long projectId, Long mergeRequestId);

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
}

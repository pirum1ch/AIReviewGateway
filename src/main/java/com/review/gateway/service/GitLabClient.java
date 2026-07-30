package com.review.gateway.service;

import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.exception.PromptSourceInvalidException;
import com.review.gateway.exception.PromptSourceUnavailableException;

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
}

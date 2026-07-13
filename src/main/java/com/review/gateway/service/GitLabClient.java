package com.review.gateway.service;

import com.review.gateway.exception.GitLabPublishException;

/**
 * Boundary interface to the GitLab discussions API (architecture §11). The real implementation
 * (a {@code RestClient}-backed bean, {@code gateway.gitlab.*} config) arrives in
 * feature/03-api-security; this stage only defines the contract {@link GitLabPublisher} programs
 * against, so it can be unit-tested against a mock now.
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
}

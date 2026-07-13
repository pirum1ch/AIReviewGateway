package com.review.gateway.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.review.gateway.exception.GitLabPublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Real {@link GitLabClient} (architecture §11): {@code POST /projects/{projectId}/merge_requests/
 * {mrIid}/discussions} via the dedicated {@code gitLabRestClient} (fixed base URL + {@code
 * PRIVATE-TOKEN} header, both from config only — {@code RestClientConfig}).
 *
 * <p>SR-10: {@code projectId}/{@code mergeRequestId} are DB-sourced but are only ever substituted as
 * templated <em>path segments</em> via {@link RestClient.RequestHeadersUriSpec#uri}'s URI-builder
 * overload — never string-concatenated into the request's host. The host is exclusively
 * {@code gateway.gitlab.base-url}, a fixed, operator-configured value (SR-15-validated as {@code https}
 * at startup by {@code GatewayProperties}).
 */
@Component
public class GitLabClientImpl implements GitLabClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabClientImpl.class);
    private static final String DISCUSSIONS_PATH = "/projects/{projectId}/merge_requests/{mergeRequestIid}/discussions";

    private final RestClient gitLabRestClient;

    public GitLabClientImpl(RestClient gitLabRestClient) {
        this.gitLabRestClient = gitLabRestClient;
    }

    @Override
    public String postDiscussion(Long projectId, Long mergeRequestId, String body) {
        try {
            DiscussionResponse response = gitLabRestClient.post()
                    .uri(DISCUSSIONS_PATH, projectId, mergeRequestId)
                    .body(new DiscussionRequest(body))
                    .retrieve()
                    .body(DiscussionResponse.class);

            if (response == null || response.id() == null || response.id().isBlank()) {
                throw new GitLabPublishException("GitLab discussion creation returned no discussion id");
            }
            return response.id();
        } catch (RestClientException failure) {
            // SR-14: never log the comment body (LLM-derived, but still treated as payload, not just
            // infra chatter) or raw exception detail beyond class/status -- the caller (GitLabPublisher)
            // already logs failure.getMessage() at WARN via the thrown exception's own message here,
            // which is deliberately generic (no comment content echoed).
            log.warn("GitLab discussion publish failed for project={} mr={}: {}",
                    projectId, mergeRequestId, failure.getClass().getSimpleName());
            throw new GitLabPublishException("Failed to publish discussion to GitLab", failure);
        }
    }

    private record DiscussionRequest(String body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscussionResponse(String id) {
    }
}

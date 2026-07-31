package com.review.gateway.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.exception.PromptSourceInvalidException;
import com.review.gateway.exception.PromptSourceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetDecoder;
import java.util.Optional;

/**
 * Real {@link GitLabClient} (architecture §11, §7): {@code POST /projects/{projectId}/merge_requests/
 * {mrIid}/discussions} via the dedicated {@code gitLabRestClient} (fixed base URL + {@code
 * PRIVATE-TOKEN} header, both from config only — {@code RestClientConfig}); the three Prompt Manager
 * read calls via the separate, read-only {@code gitLabPromptRestClient} bean (PMR-15).
 *
 * <p>SR-10/PMR-13: every id/ref/path is only ever substituted as a templated <em>path segment</em> via
 * {@link RestClient.RequestHeadersUriSpec#uri}'s URI-builder overload — never string-concatenated into
 * the request's host or path. The host is exclusively {@code gateway.gitlab.base-url}, a fixed,
 * operator-configured value (SR-15-validated as {@code https} at startup by {@code GatewayProperties}).
 * A project reference containing a {@code /} (e.g. {@code group/project}) is encoded by the same
 * templated-substitution mechanism into {@code %2F} — Spring's default {@code UriBuilderFactory}
 * encoding mode strictly encodes each URI variable's value (including {@code /}) before expansion,
 * which is exactly GitLab's required "URL-encoded project path" shape for the {@code :id} position.
 */
@Component
public class GitLabClientImpl implements GitLabClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabClientImpl.class);
    private static final String DISCUSSIONS_PATH = "/projects/{projectId}/merge_requests/{mergeRequestIid}/discussions";
    private static final String COMMITS_PATH = "/projects/{projectRef}/repository/commits/{ref}";
    private static final String RAW_FILE_PATH = "/projects/{projectRef}/repository/files/{filePath}/raw?ref={commitSha}";
    private static final String PROJECT_PATH = "/projects/{projectRef}";
    /** PMR-13: {@code commitSha} must be pinned to this shape before it ever reaches a URI. */
    private static final java.util.regex.Pattern COMMIT_SHA_PATTERN = java.util.regex.Pattern.compile("^[0-9a-f]{40}$");

    private final RestClient gitLabRestClient;
    private final RestClient gitLabPromptRestClient;

    public GitLabClientImpl(RestClient gitLabRestClient, RestClient gitLabPromptRestClient) {
        this.gitLabRestClient = gitLabRestClient;
        this.gitLabPromptRestClient = gitLabPromptRestClient;
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

    @Override
    public String resolveCommitSha(String projectRef, String ref) {
        try {
            CommitResponse response = gitLabPromptRestClient.get()
                    .uri(COMMITS_PATH, projectRef, ref)
                    .retrieve()
                    .body(CommitResponse.class);
            if (response == null || response.id() == null || response.id().isBlank()) {
                throw new PromptSourceUnavailableException("GitLab commit resolution returned no commit id");
            }
            return response.id();
        } catch (RestClientException failure) {
            // PMR-26: the log line (server-side only) may carry the failure class; the exception message
            // that reaches the HTTP response body never does (GlobalExceptionHandler returns a fixed
            // generic body for this exception type).
            log.warn("GitLab commit resolution failed: {}", failure.getClass().getSimpleName());
            throw new PromptSourceUnavailableException("Failed to resolve commit for prompt source", failure);
        }
    }

    @Override
    public String resolveDefaultBranch(String projectRef) {
        try {
            ProjectResponse response = gitLabPromptRestClient.get()
                    .uri(PROJECT_PATH, projectRef)
                    .retrieve()
                    .body(ProjectResponse.class);
            if (response == null || response.defaultBranch() == null || response.defaultBranch().isBlank()) {
                throw new PromptSourceUnavailableException("GitLab project lookup returned no default_branch");
            }
            return response.defaultBranch();
        } catch (RestClientException failure) {
            log.warn("GitLab default-branch resolution failed: {}", failure.getClass().getSimpleName());
            throw new PromptSourceUnavailableException("Failed to resolve default branch for prompt source", failure);
        }
    }

    /**
     * PMR-13: {@code filePath} is substituted as a plain URI template variable (same mechanism as
     * {@code projectRef}/{@code ref} everywhere else in this class) — GitLab's raw-file endpoint needs
     * the whole path (including any internal {@code /}) collapsed into one opaque, percent-encoded
     * segment, and Spring's default {@code UriBuilderFactory} encoding mode already does exactly that:
     * every URI template variable's value is strictly encoded (including {@code /} -&gt; {@code %2F})
     * before expansion into its placeholder. An explicit {@link UriUtils#encodePathSegment} pre-encode
     * step here would double-encode (verified: it turns {@code /} into {@code %252F}, which GitLab does
     * not accept) — this class therefore never pre-encodes, consistently, everywhere.
     *
     * <p>PMR-17: the response body is read through {@link BoundedInputStream}, bounded at
     * {@code maxBytes + 1} — never buffered into a {@code String}/{@code byte[]} first and checked after
     * (F-DC-01's mistake, one feature later). {@code Content-Length}, when present, is checked first as a
     * cheap early reject.
     *
     * <p>PMR-13: {@code commitSha} is pinned to {@code ^[0-9a-f]{40}$} before it ever reaches the URI —
     * defense in depth: every caller of this method only ever passes through a value this class's own
     * {@link #resolveCommitSha} just returned (never client input), but a URI-construction safety net
     * must not implicitly trust that invariant forever.
     */
    @Override
    public Optional<String> fetchRawFile(String projectRef, String filePath, String commitSha, int maxBytes) {
        if (commitSha == null || !COMMIT_SHA_PATTERN.matcher(commitSha).matches()) {
            throw new PromptSourceUnavailableException("commitSha does not match the expected ^[0-9a-f]{40}$ shape");
        }
        try {
            return gitLabPromptRestClient.get()
                    .uri(RAW_FILE_PATH, projectRef, filePath, commitSha)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 404) {
                            return Optional.<String>empty();
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new PromptSourceUnavailableException(
                                    "GitLab file fetch returned status " + status.value());
                        }
                        long contentLength = response.getHeaders().getContentLength();
                        if (contentLength >= 0 && contentLength > maxBytes) {
                            throw new PromptSourceInvalidException(
                                    "Prompt source file exceeds gateway.prompt.limits.max-file-bytes (Content-Length)");
                        }
                        return Optional.of(readBoundedUtf8(response.getBody(), maxBytes));
                    });
        } catch (PromptSourceInvalidException | PromptSourceUnavailableException domain) {
            throw domain;
        } catch (RestClientException failure) {
            log.warn("GitLab file fetch failed: {}", failure.getClass().getSimpleName());
            throw new PromptSourceUnavailableException("Failed to fetch prompt source file", failure);
        }
    }

    /**
     * PMR-03/PMT-18: strict UTF-8 decode (malformed input throws, never replaced with U+FFFD garbage
     * that would silently occupy token budget) plus the NUL/empty checks from the error taxonomy
     * (§10 {@code PROMPT_SOURCE_INVALID}).
     */
    private String readBoundedUtf8(InputStream rawBody, int maxBytes) {
        byte[] bytes;
        try (InputStream bounded = new BoundedInputStream(rawBody, maxBytes)) {
            bytes = bounded.readAllBytes();
        } catch (BoundedInputStream.ResponseTooLargeException tooLarge) {
            throw new PromptSourceInvalidException(
                    "Prompt source file exceeds gateway.prompt.limits.max-file-bytes", tooLarge);
        } catch (IOException e) {
            throw new PromptSourceUnavailableException("Could not read GitLab file response", e);
        }
        String decoded = strictDecodeUtf8(bytes);
        if (decoded.indexOf('\u0000') >= 0) {
            throw new PromptSourceInvalidException("Prompt source file contains a NUL byte");
        }
        if (decoded.isEmpty()) {
            throw new PromptSourceInvalidException("Prompt source file is empty");
        }
        return decoded;
    }

    private String strictDecodeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new PromptSourceInvalidException("Prompt source file is not valid UTF-8", e);
        }
    }

    private record DiscussionRequest(String body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscussionResponse(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CommitResponse(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProjectResponse(@JsonProperty("default_branch") String defaultBranch) {
    }
}

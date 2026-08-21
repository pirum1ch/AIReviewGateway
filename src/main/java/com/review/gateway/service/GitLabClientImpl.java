package com.review.gateway.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.exception.PromptSourceInvalidException;
import com.review.gateway.exception.PromptSourceUnavailableException;
import com.review.gateway.service.dto.DiffPosition;
import com.review.gateway.service.dto.DiffRefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
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
    private static final String MERGE_REQUEST_PATH = "/projects/{projectId}/merge_requests/{mergeRequestIid}";
    private static final String COMMITS_PATH = "/projects/{projectRef}/repository/commits/{ref}";
    private static final String RAW_FILE_PATH = "/projects/{projectRef}/repository/files/{filePath}/raw?ref={commitSha}";
    private static final String PROJECT_PATH = "/projects/{projectRef}";
    /** PMR-13: {@code commitSha} must be pinned to this shape before it ever reaches a URI. Reused,
     * unchanged, for DPR-07's SHA validation on {@link #fetchDiffRefs} — one implementation of the
     * "40 lowercase hex chars" lesson, not a second one (§0 of the diff-position-anchoring threat model). */
    private static final java.util.regex.Pattern COMMIT_SHA_PATTERN = java.util.regex.Pattern.compile("^[0-9a-f]{40}$");
    /** F-PM-06: {@code reviews.source_ref}/{@code review_prompt_sections.source_ref} column width. */
    private static final int MAX_DEFAULT_BRANCH_LENGTH = 200;
    private static final String POSITION_TYPE_TEXT = "text";

    private final RestClient gitLabRestClient;
    private final RestClient gitLabPromptRestClient;
    private final TextSanitizer textSanitizer;
    private final MetricsCounters metricsCounters;

    public GitLabClientImpl(RestClient gitLabRestClient, RestClient gitLabPromptRestClient, TextSanitizer textSanitizer,
                             MetricsCounters metricsCounters) {
        this.gitLabRestClient = gitLabRestClient;
        this.gitLabPromptRestClient = gitLabPromptRestClient;
        this.textSanitizer = textSanitizer;
        this.metricsCounters = metricsCounters;
    }

    /**
     * DPR-08: the position-less retry is a bounded loop (a flag, not a re-entrant call) — at most one
     * retry, triggered only by HTTP 400, only when a position was actually attached on the attempt that
     * received it. 401/403/404/429/5xx and network failures fall straight to the existing
     * {@link GitLabPublishException} transient path, exactly as before this feature.
     */
    @Override
    public String postDiscussion(Long projectId, Long mergeRequestId, String body, DiffPosition position) {
        DiffPosition attemptPosition = position;
        boolean alreadyRetried = false;
        while (true) {
            try {
                return doPostDiscussion(projectId, mergeRequestId, body, attemptPosition);
            } catch (HttpClientErrorException.BadRequest badRequest) {
                if (attemptPosition != null && !alreadyRetried) {
                    log.warn("GitLab discussion publish returned 400 with a position attached for project={} mr={}; "
                            + "retrying once without position", projectId, mergeRequestId);
                    metricsCounters.incrementPositionRejectedByGitLab();
                    attemptPosition = null;
                    alreadyRetried = true;
                    continue;
                }
                log.warn("GitLab discussion publish failed for project={} mr={}: {}",
                        projectId, mergeRequestId, badRequest.getClass().getSimpleName());
                throw new GitLabPublishException("Failed to publish discussion to GitLab", badRequest);
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
    }

    private String doPostDiscussion(Long projectId, Long mergeRequestId, String body, DiffPosition position) {
        DiscussionResponse response = gitLabRestClient.post()
                .uri(DISCUSSIONS_PATH, projectId, mergeRequestId)
                .body(new DiscussionRequest(body, toPositionRequest(position)))
                .retrieve()
                .body(DiscussionResponse.class);

        if (response == null || response.id() == null || response.id().isBlank()) {
            throw new GitLabPublishException("GitLab discussion creation returned no discussion id");
        }
        return response.id();
    }

    /**
     * DPR-03: the choke point every positioned POST goes through. Returns {@code null} (no
     * {@code position} key at all in the serialized body, {@code @JsonInclude(NON_NULL)}) unless every
     * invariant holds — never {@code /dev/null} in either path, never a half-filled line-number
     * combination (new-file interpretation only: {@code newLine} is always required), never a blank SHA.
     * Defense in depth: {@link DiffPositionResolver} and {@code GitLabPublisher} are already specified to
     * never produce an invalid {@link DiffPosition}, but this is the single place a wire body is built,
     * so it is also the single place that must refuse to emit one.
     */
    private PositionRequest toPositionRequest(DiffPosition position) {
        if (position == null) {
            return null;
        }
        if (isBlankOrDevNull(position.oldPath()) || isBlankOrDevNull(position.newPath())) {
            return null;
        }
        if (position.newLine() == null) {
            return null;
        }
        if (isBlank(position.baseSha()) || isBlank(position.startSha()) || isBlank(position.headSha())) {
            return null;
        }
        return new PositionRequest(POSITION_TYPE_TEXT, position.baseSha(), position.startSha(), position.headSha(),
                position.oldPath(), position.newPath(), position.oldLine(), position.newLine());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isBlankOrDevNull(String value) {
        return isBlank(value) || "/dev/null".equals(value);
    }

    /**
     * Diff Position Anchoring: resolves {@code diff_refs} for {@code mrIid} via the write-scoped
     * {@code gitLabRestClient} (reused, not a new credential — endorsed by the threat model §4.5: this
     * is the first *read* call on that client, but minting a third GitLab credential for one field is a
     * worse trade-off than the widened scope, which DEPLOYMENT.md documents). DPR-07: binds only
     * {@code diff_refs.{base_sha,start_sha,head_sha}} via {@code @JsonIgnoreProperties(ignoreUnknown =
     * true)} — every other MR field (title/description/labels/...) is skipped by Jackson without ever
     * being materialized into a String (§4.1 of the threat model). Never throws.
     */
    @Override
    public Optional<DiffRefs> fetchDiffRefs(Long projectId, Long mergeRequestId) {
        try {
            MergeRequestResponse response = gitLabRestClient.get()
                    .uri(MERGE_REQUEST_PATH, projectId, mergeRequestId)
                    .retrieve()
                    .body(MergeRequestResponse.class);
            return extractDiffRefs(response);
        } catch (RestClientException failure) {
            log.warn("GitLab merge request lookup failed for project={} mr={}: {}",
                    projectId, mergeRequestId, failure.getClass().getSimpleName());
            return Optional.empty();
        } catch (RuntimeException unexpected) {
            // DPR-07: no throws path -- any other runtime failure (e.g. an unexpected URI-building or
            // deserialization error) degrades to "diff refs unavailable", exactly like a network failure.
            log.warn("GitLab merge request lookup failed unexpectedly for project={} mr={}: {}",
                    projectId, mergeRequestId, unexpected.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<DiffRefs> extractDiffRefs(MergeRequestResponse response) {
        if (response == null || response.diffRefs() == null) {
            return Optional.empty();
        }
        DiffRefsResponse raw = response.diffRefs();
        String baseSha = normalizeCommitSha(raw.baseSha());
        String startSha = normalizeCommitSha(raw.startSha());
        String headSha = normalizeCommitSha(raw.headSha());
        if (baseSha == null || startSha == null || headSha == null) {
            // DPR-07: all three or none -- never a partially-populated DiffRefs.
            return Optional.empty();
        }
        return Optional.of(new DiffRefs(baseSha, startSha, headSha));
    }

    private String normalizeCommitSha(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return COMMIT_SHA_PATTERN.matcher(raw).matches() ? raw : null;
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

    /**
     * F-PM-06/PMR-25: {@code default_branch} is GitLab-repo-controlled (project-maintainer, not
     * MR-author, but still external input), same class of value as the MR target-branch name PMR-25
     * already required sanitization for. Sanitized here, once, at the single point this value first
     * enters the process (grep-verified: {@code resolveDefaultBranch} is the only caller of the GitLab
     * project-lookup endpoint) -- before it is ever persisted ({@code source_ref}), rendered into a
     * {@code toString()}, or used to key the subsequent commit-SHA/file-fetch calls. Same {@link
     * TextSanitizer} entry point section text uses, not a second implementation of the same stripping
     * logic (Cc/Cf/Zl/Zp, length-capped) -- {@link TextSanitizer#sanitizePath} rather than {@link
     * TextSanitizer#sanitizeSectionText} because a branch name, like a file path, has no legitimate
     * newline.
     */
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
            String sanitized = textSanitizer.sanitizePath(response.defaultBranch(), MAX_DEFAULT_BRANCH_LENGTH);
            if (sanitized == null) {
                // Nothing publishable survived sanitization (e.g. a branch name made entirely of
                // control/format characters) -- treat exactly like "no default_branch returned" above.
                throw new PromptSourceUnavailableException(
                        "GitLab project lookup returned a default_branch with no publishable content");
            }
            return sanitized;
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

    /**
     * DPR-03: {@code @JsonInclude(NON_NULL)} is load-bearing here -- when {@code position} is
     * {@code null} (the fallback/legacy path), the serialized body must be exactly
     * {@code {"body":"..."}}, with no {@code "position"} key at all (asserted on serialized bytes in
     * {@code GitLabClientImplTest}, not just on object state).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DiscussionRequest(String body, PositionRequest position) {
    }

    /**
     * DPR-03: {@code @JsonInclude(NON_NULL)} so {@code old_line}/{@code new_line} are <em>omitted</em>,
     * never serialized as {@code null}, per GitLab's own line-type convention (added line: {@code
     * new_line} only; context line: both).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record PositionRequest(
            @JsonProperty("position_type") String positionType,
            @JsonProperty("base_sha") String baseSha,
            @JsonProperty("start_sha") String startSha,
            @JsonProperty("head_sha") String headSha,
            @JsonProperty("old_path") String oldPath,
            @JsonProperty("new_path") String newPath,
            @JsonProperty("old_line") Integer oldLine,
            @JsonProperty("new_line") Integer newLine) {
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

    /** DPR-07: the ONLY field bound from {@code GET /projects/{id}/merge_requests/{iid}} -- every other
     * MR field (title/description/labels/...) is ignored, and never materialized (§4.1). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MergeRequestResponse(@JsonProperty("diff_refs") DiffRefsResponse diffRefs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiffRefsResponse(
            @JsonProperty("base_sha") String baseSha,
            @JsonProperty("start_sha") String startSha,
            @JsonProperty("head_sha") String headSha) {
    }
}

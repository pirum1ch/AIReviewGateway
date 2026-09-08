package com.review.gateway.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffFetchUnavailableException;
import com.review.gateway.exception.DiffIntegrityException;
import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.exception.PromptSourceInvalidException;
import com.review.gateway.exception.PromptSourceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
    /** F-PM-06: {@code reviews.source_ref}/{@code review_prompt_sections.source_ref} column width. */
    private static final int MAX_DEFAULT_BRANCH_LENGTH = 200;

    // ---- GitLab Webhook Diff Trigger (WHR-06/WHR-18/WHR-19/WHR-24) ----
    private static final String MERGE_REQUEST_PATH = "/projects/{projectId}/merge_requests/{mergeRequestIid}";
    private static final String COMPARE_PATH = "/projects/{projectId}/repository/compare";
    private static final String CHANGES_PATH = "/projects/{projectId}/merge_requests/{mergeRequestIid}/changes";
    private static final String NOTES_PATH = "/projects/{projectId}/merge_requests/{mergeRequestIid}/notes";
    private static final String MERGE_REQUESTS_LIST_PATH = "/merge_requests";
    private static final String FILE_HEAD_PATH = "/projects/{projectId}/repository/files/{filePath}";
    /** WHR-06/WHR-11: SHAs from a webhook-triggered flow, pinned before ever reaching a URI. */
    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{7,64}$");
    /** WHR-24: bounded retry budget for GitLab {@code 429} responses on the diff-fetch path. */
    private static final int MAX_429_ATTEMPTS = 3;
    private static final long MAX_RETRY_AFTER_SECONDS = 5;

    private final RestClient gitLabRestClient;
    private final RestClient gitLabPromptRestClient;
    private final RestClient gitLabDiffRestClient;
    private final TextSanitizer textSanitizer;
    private final GatewayProperties properties;
    private final ObjectMapper diffObjectMapper = new ObjectMapper();

    public GitLabClientImpl(RestClient gitLabRestClient, RestClient gitLabPromptRestClient,
                             RestClient gitLabDiffRestClient, TextSanitizer textSanitizer,
                             GatewayProperties properties) {
        this.gitLabRestClient = gitLabRestClient;
        this.gitLabPromptRestClient = gitLabPromptRestClient;
        this.gitLabDiffRestClient = gitLabDiffRestClient;
        this.textSanitizer = textSanitizer;
        this.properties = properties;
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

    // ================= GitLab Webhook Diff Trigger (read-only, gitLabDiffRestClient) =================

    @Override
    public MergeRequestSnapshot fetchMergeRequest(Long projectId, Long mergeRequestIid) {
        return withRetry429(() -> {
            try {
                MergeRequestApiResponse response = gitLabDiffRestClient.get()
                        .uri(MERGE_REQUEST_PATH, projectId, mergeRequestIid)
                        .retrieve()
                        .body(MergeRequestApiResponse.class);
                if (response == null || response.diffRefs() == null || response.diffRefs().baseSha() == null
                        || response.diffRefs().headSha() == null) {
                    throw new DiffFetchUnavailableException("GitLab merge request lookup returned no usable diff_refs");
                }
                List<Long> reviewerIds = response.reviewers() == null ? List.of() : response.reviewers().stream()
                        .filter(Objects::nonNull)
                        .map(ReviewerRefApi::id)
                        .filter(Objects::nonNull)
                        .toList();
                return new MergeRequestSnapshot(response.state(), response.diffRefs().baseSha(),
                        response.diffRefs().headSha(), reviewerIds);
            } catch (HttpClientErrorException.TooManyRequests tooMany) {
                throw new RateLimitedSignal(parseRetryAfter(tooMany.getResponseHeaders()));
            } catch (RestClientException failure) {
                log.warn("GitLab merge request lookup failed: {}", failure.getClass().getSimpleName());
                throw new DiffFetchUnavailableException("Failed to fetch merge request from GitLab", failure);
            }
        });
    }

    @Override
    public CompareResult compareDiff(Long projectId, String baseSha, String headSha) {
        requireValidSha(baseSha);
        requireValidSha(headSha);
        return withRetry429(() -> gitLabDiffRestClient.get()
                .uri(uriBuilder -> uriBuilder.path(COMPARE_PATH)
                        .queryParam("from", baseSha)
                        .queryParam("to", headSha)
                        .queryParam("unidiff", "true")
                        .build(projectId))
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == 429) {
                        throw new RateLimitedSignal(parseRetryAfter(response.getHeaders()));
                    }
                    if (status.value() == 404) {
                        // WHR-20: base_sha/head_sha unreachable, typically a force-push race -- deterministic
                        // (never retried), distinct from an ordinary transient fetch failure.
                        throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNAVAILABLE,
                                "GitLab /repository/compare returned 404 for the given sha pair (unreachable, "
                                        + "likely a force-push race)");
                    }
                    if (!status.is2xxSuccessful()) {
                        throw new DiffFetchUnavailableException("GitLab /repository/compare returned status " + status.value());
                    }
                    byte[] body = readBoundedBody(response);
                    CompareApiResponse parsed = parseJson(body, CompareApiResponse.class);
                    List<DiffEntry> entries = parsed.diffs() == null ? List.of()
                            : parsed.diffs().stream().map(GitLabClientImpl::toDiffEntry).toList();
                    return new CompareResult(Boolean.TRUE.equals(parsed.compareTimeout()), entries);
                }));
    }

    @Override
    public boolean fetchOverflowFlag(Long projectId, Long mergeRequestIid) {
        return withRetry429(() -> gitLabDiffRestClient.get()
                .uri(CHANGES_PATH, projectId, mergeRequestIid)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == 429) {
                        throw new RateLimitedSignal(parseRetryAfter(response.getHeaders()));
                    }
                    if (status.value() == 404) {
                        // WHR-16: its own distinct, greppable log message -- not a generic network blip.
                        // This is not (yet) an observed failure on the probed instance; it is the runtime
                        // handling for a future GitLab version that might remove the deprecated endpoint.
                        log.error("GitLab deprecated /merge_requests/:iid/changes returned 404 (endpoint "
                                + "may have been removed) -- treating diff integrity as unverifiable, project={} mr={}",
                                projectId, mergeRequestIid);
                        throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNAVAILABLE,
                                "GitLab deprecated /changes endpoint returned 404 (integrity unverifiable)");
                    }
                    if (!status.is2xxSuccessful()) {
                        throw new DiffFetchUnavailableException("GitLab /changes returned status " + status.value());
                    }
                    byte[] body = readBoundedBody(response);
                    ChangesApiResponse parsed = parseJson(body, ChangesApiResponse.class);
                    return Boolean.TRUE.equals(parsed.overflow());
                }));
    }

    /**
     * WHR-15b: no request is issued at all unless {@code ref} already matches
     * {@link #SHA_PATTERN} — the same defense-in-depth discipline as {@link #fetchRawFile}'s
     * {@code commitSha} check.
     */
    @Override
    public long headFileSize(Long projectId, String filePath, String ref) {
        if (ref == null || !SHA_PATTERN.matcher(ref).matches()) {
            throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED,
                    "ref for the WHR-15b HEAD size-check does not match the expected shape");
        }
        return withRetry429(() -> gitLabDiffRestClient.method(HttpMethod.HEAD)
                .uri(uriBuilder -> uriBuilder.path(FILE_HEAD_PATH).queryParam("ref", ref).build(projectId, filePath))
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.value() == 429) {
                        throw new RateLimitedSignal(parseRetryAfter(response.getHeaders()));
                    }
                    if (!status.is2xxSuccessful()) {
                        // Fail-closed (WHR-15b): a claimed new/deleted file whose size cannot be verified is
                        // treated exactly like a confirmed truncation, never like "assume it's fine".
                        throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED,
                                "Could not verify claimed new/deleted file size via HEAD (status=" + status.value() + ")");
                    }
                    String sizeHeader = response.getHeaders().getFirst("X-Gitlab-Size");
                    if (sizeHeader == null || sizeHeader.isBlank()) {
                        throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED,
                                "GitLab HEAD file response carried no X-Gitlab-Size header");
                    }
                    try {
                        return Long.parseLong(sizeHeader.trim());
                    } catch (NumberFormatException notNumeric) {
                        throw new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED,
                                "GitLab X-Gitlab-Size header was not a valid number");
                    }
                }));
    }

    /**
     * WHT-20/§4.4: NEVER throws — any failure here (network, oversized response, malformed JSON, page-cap
     * exceeded) is treated as "ambiguous", which the caller's diagnostic-comment logic must read as
     * "do not post" rather than crash or retry.
     */
    @Override
    public List<Note> listRecentNotes(Long projectId, Long mergeRequestIid) {
        try {
            List<Note> notes = new ArrayList<>();
            int maxPages = Math.max(1, properties.getGitlab().getDiff().getMaxPages());
            for (int page = 1; page <= maxPages; page++) {
                int currentPage = page;
                PageResult<NoteApiResponse> result = withRetry429(() -> gitLabDiffRestClient.get()
                        .uri(uriBuilder -> uriBuilder.path(NOTES_PATH)
                                .queryParam("per_page", "100")
                                .queryParam("page", currentPage)
                                .build(projectId, mergeRequestIid))
                        .exchange((request, response) -> fetchPage(response, NoteApiResponse.class)));
                for (NoteApiResponse item : result.items()) {
                    notes.add(new Note(item.author() == null ? null : item.author().id(), item.body()));
                }
                if (!result.hasNext()) {
                    break;
                }
            }
            return List.copyOf(notes);
        } catch (RuntimeException failure) {
            log.warn("GitLab notes read failed for project={} mr={}; treating as empty for the anti-duplicate "
                            + "diagnostic-comment check: {}", projectId, mergeRequestIid, failure.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public List<MergeRequestRef> listOpenMergeRequestsForReviewer(String reviewerUsername, Instant updatedAfter,
                                                                    int maxPages, int maxResults) {
        List<MergeRequestRef> results = new ArrayList<>();
        int effectiveMaxPages = Math.max(1, maxPages);
        for (int page = 1; page <= effectiveMaxPages && results.size() < maxResults; page++) {
            int currentPage = page;
            PageResult<MergeRequestListItemApiResponse> result = withRetry429(() -> gitLabDiffRestClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(MERGE_REQUESTS_LIST_PATH)
                                .queryParam("reviewer_username", reviewerUsername)
                                .queryParam("state", "opened")
                                .queryParam("scope", "all")
                                .queryParam("per_page", "100")
                                .queryParam("page", currentPage);
                        if (updatedAfter != null) {
                            uriBuilder.queryParam("updated_after", updatedAfter.toString());
                        }
                        return uriBuilder.build();
                    })
                    .exchange((request, response) -> fetchPage(response, MergeRequestListItemApiResponse.class)));
            for (MergeRequestListItemApiResponse item : result.items()) {
                if (results.size() >= maxResults) {
                    break;
                }
                if (item.projectId() != null && item.iid() != null) {
                    results.add(new MergeRequestRef(item.projectId(), item.iid(), item.sha()));
                }
            }
            if (!result.hasNext()) {
                break;
            }
        }
        return List.copyOf(results);
    }

    private <T> PageResult<T> fetchPage(org.springframework.http.client.ClientHttpResponse response, Class<T> elementType)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.value() == 429) {
            throw new RateLimitedSignal(parseRetryAfter(response.getHeaders()));
        }
        if (!status.is2xxSuccessful()) {
            throw new DiffFetchUnavailableException("GitLab paginated read returned status " + status.value());
        }
        byte[] body = readBoundedBody(response);
        List<T> items = parseJsonList(body, elementType);
        String nextPage = response.getHeaders().getFirst("X-Next-Page");
        return new PageResult<>(items, nextPage != null && !nextPage.isBlank());
    }

    private void requireValidSha(String sha) {
        if (sha == null || !SHA_PATTERN.matcher(sha).matches()) {
            throw new DiffFetchUnavailableException("sha does not match the expected ^[0-9a-f]{7,64}$ shape");
        }
    }

    /** WHR-19: bound at {@code gateway.gitlab.diff.max-response-bytes + 1}, never buffered-then-checked. */
    private byte[] readBoundedBody(org.springframework.http.client.ClientHttpResponse response) {
        long maxBytes = properties.getGitlab().getDiff().getMaxResponseBytes();
        try {
            long contentLength = response.getHeaders().getContentLength();
            if (contentLength >= 0 && contentLength > maxBytes) {
                throw new DiffFetchUnavailableException(
                        "GitLab response exceeded gateway.gitlab.diff.max-response-bytes (Content-Length)");
            }
            try (InputStream bounded = new BoundedInputStream(response.getBody(), maxBytes)) {
                return bounded.readAllBytes();
            }
        } catch (BoundedInputStream.ResponseTooLargeException tooLarge) {
            throw new DiffFetchUnavailableException(
                    "GitLab response exceeded gateway.gitlab.diff.max-response-bytes", tooLarge);
        } catch (IOException io) {
            throw new DiffFetchUnavailableException("Could not read GitLab response body", io);
        }
    }

    private <T> T parseJson(byte[] body, Class<T> type) {
        try {
            return diffObjectMapper.readValue(body, type);
        } catch (IOException e) {
            throw new DiffFetchUnavailableException("GitLab returned malformed JSON", e);
        }
    }

    private <T> List<T> parseJsonList(byte[] body, Class<T> elementType) {
        try {
            JavaType listType = diffObjectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
            return diffObjectMapper.readValue(body, listType);
        } catch (IOException e) {
            throw new DiffFetchUnavailableException("GitLab returned malformed JSON", e);
        }
    }

    private static DiffEntry toDiffEntry(DiffEntryApi api) {
        return new DiffEntry(api.oldPath(), api.newPath(), api.aMode(), api.bMode(),
                Boolean.TRUE.equals(api.newFile()), Boolean.TRUE.equals(api.deletedFile()),
                Boolean.TRUE.equals(api.renamedFile()), api.diff() == null ? "" : api.diff());
    }

    private long parseRetryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return 1;
        }
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * WHR-24: honours GitLab's {@code 429}/{@code Retry-After} with a bounded retry budget
     * ({@value #MAX_429_ATTEMPTS} attempts total, each wait capped at {@value #MAX_RETRY_AFTER_SECONDS}s)
     * — never an unbounded retry loop, never ignored.
     */
    private <T> T withRetry429(Supplier<T> attempt) {
        for (int i = 0; i < MAX_429_ATTEMPTS; i++) {
            try {
                return attempt.get();
            } catch (RateLimitedSignal signal) {
                if (i == MAX_429_ATTEMPTS - 1) {
                    throw new DiffFetchUnavailableException("GitLab rate limit (429) exceeded the retry budget");
                }
                sleepBounded(signal.retryAfterSeconds);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private void sleepBounded(long retryAfterSeconds) {
        long millis = Math.max(0, Math.min(retryAfterSeconds, MAX_RETRY_AFTER_SECONDS)) * 1000L;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DiffFetchUnavailableException("Interrupted while waiting for GitLab rate limit", e);
        }
    }

    /** Internal signal caught only by {@link #withRetry429}; never escapes a public method. */
    private static final class RateLimitedSignal extends RuntimeException {
        private final long retryAfterSeconds;

        RateLimitedSignal(long retryAfterSeconds) {
            super(null, null, false, false);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private record PageResult<T>(List<T> items, boolean hasNext) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MergeRequestApiResponse(String state, List<ReviewerRefApi> reviewers,
                                            @JsonProperty("diff_refs") DiffRefsApi diffRefs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReviewerRefApi(Long id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiffRefsApi(@JsonProperty("base_sha") String baseSha, @JsonProperty("head_sha") String headSha) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CompareApiResponse(@JsonProperty("compare_timeout") Boolean compareTimeout,
                                       List<DiffEntryApi> diffs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiffEntryApi(@JsonProperty("old_path") String oldPath, @JsonProperty("new_path") String newPath,
                                 @JsonProperty("a_mode") String aMode, @JsonProperty("b_mode") String bMode,
                                 @JsonProperty("new_file") Boolean newFile,
                                 @JsonProperty("deleted_file") Boolean deletedFile,
                                 @JsonProperty("renamed_file") Boolean renamedFile, String diff) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChangesApiResponse(Boolean overflow) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NoteApiResponse(NoteAuthorApi author, String body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NoteAuthorApi(Long id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MergeRequestListItemApiResponse(@JsonProperty("project_id") Long projectId, Long iid, String sha) {
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

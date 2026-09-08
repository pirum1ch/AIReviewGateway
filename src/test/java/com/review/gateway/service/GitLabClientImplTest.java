package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffFetchUnavailableException;
import com.review.gateway.exception.DiffIntegrityException;
import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.exception.PromptSourceInvalidException;
import com.review.gateway.exception.PromptSourceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitLabClientImplTest {

    private static final String BASE_URL = "https://gitlab.example.test/api/v4";

    private MockRestServiceServer mockServer;
    private MockRestServiceServer promptMockServer;
    private MockRestServiceServer diffMockServer;
    private GitLabClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("PRIVATE-TOKEN", "test-gitlab-token-0123456789012345");
        mockServer = MockRestServiceServer.bindTo(builder).build();

        RestClient.Builder promptBuilder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("PRIVATE-TOKEN", "test-gitlab-prompt-token-01234567890");
        promptMockServer = MockRestServiceServer.bindTo(promptBuilder).build();

        RestClient.Builder diffBuilder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("PRIVATE-TOKEN", "test-gitlab-diff-token-012345678901234");
        diffMockServer = MockRestServiceServer.bindTo(diffBuilder).build();

        client = new GitLabClientImpl(builder.build(), promptBuilder.build(), diffBuilder.build(),
                new TextSanitizer(), new com.review.gateway.config.GatewayProperties());
    }

    // ---- postDiscussion (existing behavior, unchanged) ----

    @Test
    void postsDiscussionAndReturnsItsId() {
        mockServer.expect(requestTo(BASE_URL + "/projects/10/merge_requests/5/discussions"))
                .andExpect(method(POST))
                .andExpect(header("PRIVATE-TOKEN", "test-gitlab-token-0123456789012345"))
                .andRespond(withSuccess("""
                        {"id": "discussion-abc-123", "individual_note": true}
                        """, MediaType.APPLICATION_JSON));

        String discussionId = client.postDiscussion(10L, 5L, "a sanitized comment body");

        assertThat(discussionId).isEqualTo("discussion-abc-123");
        mockServer.verify();
    }

    @Test
    void projectAndMergeRequestIdsAreSubstitutedAsPathSegmentsNotHostConcatenation() {
        // SR-10: even DB-sourced numeric ids must never influence the request's host -- only the path.
        mockServer.expect(requestTo(BASE_URL + "/projects/999999/merge_requests/123456/discussions"))
                .andRespond(withSuccess("""
                        {"id": "d-1"}
                        """, MediaType.APPLICATION_JSON));

        client.postDiscussion(999999L, 123456L, "body");

        mockServer.verify();
    }

    @Test
    void serverErrorIsTranslatedToGitLabPublishException() {
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.postDiscussion(1L, 1L, "body"))
                .isInstanceOf(GitLabPublishException.class);
        mockServer.verify();
    }

    @Test
    void responseWithoutAnIdIsTreatedAsAFailure() {
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.postDiscussion(1L, 1L, "body"))
                .isInstanceOf(GitLabPublishException.class);
    }

    // ---- resolveCommitSha (PMR-13/PMR-15/PMR-26) ----

    @Test
    void resolveCommitShaReturnsTheCommitId() {
        promptMockServer.expect(requestTo(BASE_URL + "/projects/42/repository/commits/main"))
                .andExpect(method(GET))
                .andExpect(header("PRIVATE-TOKEN", "test-gitlab-prompt-token-01234567890"))
                .andRespond(withSuccess("""
                        {"id": "abc123def456"}
                        """, MediaType.APPLICATION_JSON));

        String sha = client.resolveCommitSha("42", "main");

        assertThat(sha).isEqualTo("abc123def456");
        promptMockServer.verify();
    }

    @Test
    void resolveCommitShaUrlEncodesAGroupPathProjectReference() {
        // PMR-13: a project ref containing '/' (group/project path form) must be encoded into %2F as a
        // single opaque path segment, exactly as GitLab's API requires for the :id position.
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/org%2Fteam-a%2Fai-review-prompts/repository/commits/main"))
                .andRespond(withSuccess("""
                        {"id": "sha1"}
                        """, MediaType.APPLICATION_JSON));

        client.resolveCommitSha("org/team-a/ai-review-prompts", "main");

        promptMockServer.verify();
    }

    @Test
    void resolveCommitSha404IsUndifferentiatedFromOtherFailures() {
        // PMR-26: coarse and undifferentiated -- always PromptSourceUnavailableException, never a
        // distinguishable "not found" vs "no access" signal for this call.
        promptMockServer.expect(requestTo(BASE_URL + "/projects/42/repository/commits/main"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.resolveCommitSha("42", "main"))
                .isInstanceOf(PromptSourceUnavailableException.class);
    }

    @Test
    void resolveCommitShaServerErrorIsUnavailable() {
        promptMockServer.expect(requestTo(BASE_URL + "/projects/42/repository/commits/main"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.resolveCommitSha("42", "main"))
                .isInstanceOf(PromptSourceUnavailableException.class);
    }

    @Test
    void resolveCommitShaUsesThePromptClientNeverTheWriteClient() {
        promptMockServer.expect(requestTo(BASE_URL + "/projects/42/repository/commits/main"))
                .andExpect(header("PRIVATE-TOKEN", "test-gitlab-prompt-token-01234567890"))
                .andRespond(withSuccess("""
                        {"id": "sha1"}
                        """, MediaType.APPLICATION_JSON));

        client.resolveCommitSha("42", "main");

        promptMockServer.verify();
        mockServer.verify(); // zero interactions on the write-scoped client
    }

    // ---- resolveDefaultBranch ----

    @Test
    void resolveDefaultBranchReturnsTheBranchName() {
        promptMockServer.expect(requestTo(BASE_URL + "/projects/42"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"id": 42, "default_branch": "main", "other_field": "ignored"}
                        """, MediaType.APPLICATION_JSON));

        String branch = client.resolveDefaultBranch("42");

        assertThat(branch).isEqualTo("main");
    }

    @Test
    void resolveDefaultBranch404IsUnavailable() {
        promptMockServer.expect(requestTo(BASE_URL + "/projects/42"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.resolveDefaultBranch("42"))
                .isInstanceOf(PromptSourceUnavailableException.class);
    }

    /**
     * F-PM-06 regression: {@code default_branch} is repo-controlled input (PMR-25), sanitized at its
     * single entry point via the same {@link TextSanitizer} section text uses -- a bidi-override
     * character (Trojan-Source class, U+202E here) must not survive into the returned branch name.
     */
    @Test
    void resolveDefaultBranchStripsControlAndFormatCharacters() {
        String rawBranch = "featu‮re/evil";
        promptMockServer.expect(requestTo(BASE_URL + "/projects/42"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"id\": 42, \"default_branch\": \"" + rawBranch + "\"}",
                        MediaType.APPLICATION_JSON));

        String branch = client.resolveDefaultBranch("42");

        assertThat(branch).isEqualTo("feature/evil");
        assertThat(branch).doesNotContain("‮");
    }

    /** F-PM-06: caps at 200 chars, below {@code reviews.source_ref VARCHAR(256)}, never overflows the column. */
    @Test
    void resolveDefaultBranchCapsExcessiveLength() {
        String rawBranch = "f".repeat(400);
        promptMockServer.expect(requestTo(BASE_URL + "/projects/42"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"id\": 42, \"default_branch\": \"" + rawBranch + "\"}",
                        MediaType.APPLICATION_JSON));

        String branch = client.resolveDefaultBranch("42");

        assertThat(branch).hasSize(200);
        assertThat(branch).endsWith("...");
    }

    /** F-PM-06 scope guard: a branch name that sanitizes to nothing publishable is treated as unavailable, not blank/null. */
    @Test
    void resolveDefaultBranchThatSanitizesToNothingIsUnavailable() {
        String rawBranch = "‮‮‮"; // entirely bidi-override control characters
        promptMockServer.expect(requestTo(BASE_URL + "/projects/42"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"id\": 42, \"default_branch\": \"" + rawBranch + "\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.resolveDefaultBranch("42"))
                .isInstanceOf(PromptSourceUnavailableException.class);
    }

    // ---- fetchRawFile (PMR-13/PMR-17) ----

    @Test
    void fetchRawFileReturnsDecodedContentOnSuccess() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/prompts%2Fbase.md/raw?ref=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"))
                .andExpect(method(GET))
                .andRespond(withSuccess("hello prompt content", MediaType.TEXT_PLAIN));

        Optional<String> content = client.fetchRawFile("42", "prompts/base.md", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", 1000);

        assertThat(content).contains("hello prompt content");
    }

    @Test
    void fetchRawFile404ReturnsEmpty() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/missing.md/raw?ref=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        Optional<String> content = client.fetchRawFile("42", "missing.md", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", 1000);

        assertThat(content).isEmpty();
    }

    @Test
    void fetchRawFileServerErrorIsUnavailable() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/x.md/raw?ref=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchRawFile("42", "x.md", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", 1000))
                .isInstanceOf(PromptSourceUnavailableException.class);
    }

    @Test
    void fetchRawFileOversizedByContentLengthIsRejectedWithoutReadingBody() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/big.md/raw?ref=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"))
                .andRespond(withSuccess("x".repeat(50), MediaType.TEXT_PLAIN)
                        .header("Content-Length", "50"));

        assertThatThrownBy(() -> client.fetchRawFile("42", "big.md", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", 10))
                .isInstanceOf(PromptSourceInvalidException.class);
    }

    @Test
    void fetchRawFileOversizedByStreamingIsRejectedEvenWithoutContentLengthHeader() {
        // PMR-17: the bound must be enforced while reading, not just via Content-Length.
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/big.md/raw?ref=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"))
                .andRespond(withSuccess("x".repeat(50), MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.fetchRawFile("42", "big.md", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", 10))
                .isInstanceOf(PromptSourceInvalidException.class);
    }

    @Test
    void fetchRawFileEmptyBodyIsInvalid() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/empty.md/raw?ref=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"))
                .andRespond(withSuccess("", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.fetchRawFile("42", "empty.md", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", 1000))
                .isInstanceOf(PromptSourceInvalidException.class);
    }

    @Test
    void fetchRawFileContainingNulByteIsInvalid() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/nul.md/raw?ref=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"))
                .andRespond(withSuccess("hello\u0000world", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.fetchRawFile("42", "nul.md", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", 1000))
                .isInstanceOf(PromptSourceInvalidException.class);
    }

    @Test
    void fetchRawFileNestedPathIsUrlEncodedAsOneOpaqueSegment() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/.ai-review%2Fcode-rules.md/raw?ref=a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"))
                .andRespond(withSuccess("rules content", MediaType.TEXT_PLAIN));

        Optional<String> content = client.fetchRawFile("42", ".ai-review/code-rules.md", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", 1000);

        assertThat(content).contains("rules content");
    }

    @Test
    void fetchRawFileRejectsACommitShaNotMatchingTheExpectedShapeBeforeIssuingAnyRequest() {
        // PMR-13: defense in depth -- pinned to ^[0-9a-f]{40}$ before it ever reaches the URI, even
        // though every real caller only ever passes through resolveCommitSha's own output.
        assertThatThrownBy(() -> client.fetchRawFile("42", "base.md", "not-a-real-sha", 1000))
                .isInstanceOf(PromptSourceUnavailableException.class);
        assertThatThrownBy(() -> client.fetchRawFile("42", "base.md", "abc123", 1000))
                .isInstanceOf(PromptSourceUnavailableException.class);
        assertThatThrownBy(() -> client.fetchRawFile("42", "base.md", null, 1000))
                .isInstanceOf(PromptSourceUnavailableException.class);
        promptMockServer.verify(); // no request was ever issued for any of the three
    }

    // ==================== GitLab Webhook Diff Trigger (WHR-03/06/15b/16/18/19/20) ====================

    // ---- fetchMergeRequest (WHR-03) ----

    @Test
    void fetchMergeRequestReturnsStateShasAndReviewerIds() {
        diffMockServer.expect(requestTo(BASE_URL + "/projects/10/merge_requests/5"))
                .andExpect(method(GET))
                .andExpect(header("PRIVATE-TOKEN", "test-gitlab-diff-token-012345678901234"))
                .andRespond(withSuccess("""
                        {"state": "opened", "reviewers": [{"id": 35}, {"id": 7}],
                         "diff_refs": {"base_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                        "head_sha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}
                        """, MediaType.APPLICATION_JSON));

        GitLabClient.MergeRequestSnapshot snapshot = client.fetchMergeRequest(10L, 5L);

        assertThat(snapshot.state()).isEqualTo("opened");
        assertThat(snapshot.baseSha()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(snapshot.headSha()).isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(snapshot.reviewerIds()).containsExactly(35L, 7L);
    }

    @Test
    void fetchMergeRequestFailureIsTransient() {
        diffMockServer.expect(requestTo(BASE_URL + "/projects/10/merge_requests/5"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchMergeRequest(10L, 5L))
                .isInstanceOf(DiffFetchUnavailableException.class);
    }

    @Test
    void fetchMergeRequestTransportFailureIsTranslatedNotRawlyPropagated() {
        diffMockServer.expect(requestTo(BASE_URL + "/projects/10/merge_requests/5"))
                .andRespond(request -> {
                    throw new java.io.IOException("connection refused");
                });

        assertThatThrownBy(() -> client.fetchMergeRequest(10L, 5L))
                .isInstanceOf(DiffFetchUnavailableException.class);
    }

    @Test
    void fetchMergeRequestOversizedResponseIsRejectedThroughTheSameBoundedReadAsCompareDiff() {
        // WHT-15/WHR-19 (QA finding): fetchMergeRequest previously bound Jackson directly against the
        // raw response (.retrieve().body(...)), buffering the ENTIRE body before any size check ran --
        // now routed through the same readBoundedBody discipline as compareDiff/fetchOverflowFlag.
        GatewayProperties smallLimit = new GatewayProperties();
        smallLimit.getGitlab().getDiff().setMaxResponseBytes(10);
        org.springframework.web.client.RestClient.Builder tightDiffBuilder = org.springframework.web.client.RestClient.builder()
                .baseUrl(BASE_URL).defaultHeader("PRIVATE-TOKEN", "t");
        MockRestServiceServer tightServer = MockRestServiceServer.bindTo(tightDiffBuilder).build();
        GitLabClientImpl underTest = new GitLabClientImpl(
                org.springframework.web.client.RestClient.builder().baseUrl(BASE_URL).build(),
                org.springframework.web.client.RestClient.builder().baseUrl(BASE_URL).build(),
                tightDiffBuilder.build(), new TextSanitizer(), smallLimit);

        tightServer.expect(requestTo(BASE_URL + "/projects/10/merge_requests/5"))
                .andRespond(withSuccess("x".repeat(50), MediaType.APPLICATION_JSON).header("Content-Length", "50"));

        assertThatThrownBy(() -> underTest.fetchMergeRequest(10L, 5L))
                .isInstanceOf(DiffFetchUnavailableException.class);
    }

    @Test
    void fetchMergeRequestMissingDiffRefsIsTransient() {
        diffMockServer.expect(requestTo(BASE_URL + "/projects/10/merge_requests/5"))
                .andRespond(withSuccess("""
                        {"state": "opened", "reviewers": []}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchMergeRequest(10L, 5L))
                .isInstanceOf(DiffFetchUnavailableException.class);
    }

    // ---- compareDiff (WHR-06/WHR-16/WHR-19/WHR-20) ----

    @Test
    void compareDiffBuildsQueryParamsAndParsesEntries() {
        diffMockServer.expect(requestTo(BASE_URL
                        + "/projects/10/repository/compare?from=aaaaaaa&to=bbbbbbb&unidiff=true"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"compare_timeout": false, "diffs": [
                            {"old_path": "a.txt", "new_path": "a.txt", "a_mode": "100644", "b_mode": "100644",
                             "new_file": false, "deleted_file": false, "renamed_file": false,
                             "diff": "@@ -1 +1 @@\\n-old\\n+new\\n"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        GitLabClient.CompareResult result = client.compareDiff(10L, "aaaaaaa", "bbbbbbb");

        assertThat(result.compareTimeout()).isFalse();
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().get(0).oldPath()).isEqualTo("a.txt");
        assertThat(result.files().get(0).diff()).contains("+new");
    }

    @Test
    void compareDiffRejectsAMalformedShaWithoutIssuingAnyRequest() {
        assertThatThrownBy(() -> client.compareDiff(10L, "not-hex!!", "bbbbbbb"))
                .isInstanceOf(DiffFetchUnavailableException.class);
        diffMockServer.verify(); // zero interactions
    }

    @Test
    void compareDiff404IsAnIntegrityFailureNotATransientOne() {
        // WHR-20: a force-push race (sha pair unreachable) is deterministic, never retried.
        diffMockServer.expect(requestTo(BASE_URL
                        + "/projects/10/repository/compare?from=aaaaaaa&to=bbbbbbb&unidiff=true"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.compareDiff(10L, "aaaaaaa", "bbbbbbb"))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_UNAVAILABLE);
    }

    @Test
    void compareDiffOversizedByContentLengthIsRejectedWithoutReadingBody() {
        GatewayProperties smallLimit = new GatewayProperties();
        smallLimit.getGitlab().getDiff().setMaxResponseBytes(10);
        GitLabClientImpl tightClient = new GitLabClientImpl(
                RestClient.builder().baseUrl(BASE_URL).build(),
                RestClient.builder().baseUrl(BASE_URL).build(),
                RestClient.builder().baseUrl(BASE_URL).defaultHeader("PRIVATE-TOKEN", "t").build(),
                new TextSanitizer(), smallLimit);
        RestClient.Builder tightDiffBuilder = RestClient.builder().baseUrl(BASE_URL)
                .defaultHeader("PRIVATE-TOKEN", "t");
        MockRestServiceServer tightServer = MockRestServiceServer.bindTo(tightDiffBuilder).build();
        GitLabClientImpl underTest = new GitLabClientImpl(
                RestClient.builder().baseUrl(BASE_URL).build(),
                RestClient.builder().baseUrl(BASE_URL).build(),
                tightDiffBuilder.build(), new TextSanitizer(), smallLimit);

        tightServer.expect(requestTo(BASE_URL
                        + "/projects/10/repository/compare?from=aaaaaaa&to=bbbbbbb&unidiff=true"))
                .andRespond(withSuccess("x".repeat(50), MediaType.APPLICATION_JSON)
                        .header("Content-Length", "50"));

        assertThatThrownBy(() -> underTest.compareDiff(10L, "aaaaaaa", "bbbbbbb"))
                .isInstanceOf(DiffFetchUnavailableException.class);
    }

    // ---- WHR-24: bounded (never unbounded) retry on GitLab 429, honouring Retry-After ----

    @Test
    void compareDiffTransportFailureIsTranslatedNotRawlyPropagated() {
        // QA finding: a genuine transport-level failure (GitLab literally unreachable -- no HTTP response
        // at all, unlike a 4xx/5xx) previously escaped withRetry429 as a raw RestClientException, breaking
        // WebhookReviewTriggerService's "never throws (WHR-07)" contract, which catches only
        // DiffFetchUnavailableException/DiffIntegrityException.
        diffMockServer.expect(requestTo(BASE_URL
                        + "/projects/10/repository/compare?from=aaaaaaa&to=bbbbbbb&unidiff=true"))
                .andRespond(request -> {
                    throw new java.io.IOException("connection refused");
                });

        assertThatThrownBy(() -> client.compareDiff(10L, "aaaaaaa", "bbbbbbb"))
                .isInstanceOf(DiffFetchUnavailableException.class);
    }

    @Test
    void compareDiffRetriesOn429AndSucceedsWithinTheRetryBudget() {
        // Retry-After: 0 keeps this test fast (sleepBounded(0) does not actually sleep).
        diffMockServer.expect(requestTo(BASE_URL
                        + "/projects/10/repository/compare?from=aaaaaaa&to=bbbbbbb&unidiff=true"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "0"));
        diffMockServer.expect(requestTo(BASE_URL
                        + "/projects/10/repository/compare?from=aaaaaaa&to=bbbbbbb&unidiff=true"))
                .andRespond(withSuccess("""
                        {"compare_timeout": false, "diffs": []}
                        """, MediaType.APPLICATION_JSON));

        GitLabClient.CompareResult result = client.compareDiff(10L, "aaaaaaa", "bbbbbbb");

        assertThat(result.files()).isEmpty();
        diffMockServer.verify(); // exactly the two expected requests -- not more, not fewer
    }

    @Test
    void compareDiffGivesUpAfterTheBoundedRetryBudgetIsExhausted() {
        // WHR-24: MAX_429_ATTEMPTS=3 total attempts -- a persistent 429 must NOT retry forever.
        for (int i = 0; i < 3; i++) {
            diffMockServer.expect(requestTo(BASE_URL
                            + "/projects/10/repository/compare?from=aaaaaaa&to=bbbbbbb&unidiff=true"))
                    .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                            .header("Retry-After", "0"));
        }

        assertThatThrownBy(() -> client.compareDiff(10L, "aaaaaaa", "bbbbbbb"))
                .isInstanceOf(DiffFetchUnavailableException.class);
        diffMockServer.verify(); // exactly 3 attempts -- the bounded budget, not an unbounded loop
    }

    // ---- fetchOverflowFlag (WHR-16) ----

    @Test
    void fetchOverflowFlagReturnsTrueWhenSet() {
        diffMockServer.expect(requestTo(BASE_URL + "/projects/10/merge_requests/5/changes"))
                .andRespond(withSuccess("{\"overflow\": true}", MediaType.APPLICATION_JSON));

        assertThat(client.fetchOverflowFlag(10L, 5L)).isTrue();
    }

    @Test
    void fetchOverflowFlag404IsIntegrityUnverifiable() {
        diffMockServer.expect(requestTo(BASE_URL + "/projects/10/merge_requests/5/changes"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchOverflowFlag(10L, 5L))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_UNAVAILABLE);
    }

    // ---- headFileSize (WHR-15b) ----

    @Test
    void headFileSizeParsesTheGitlabSizeHeader() {
        diffMockServer.expect(requestTo(
                        BASE_URL + "/projects/10/repository/files/a.txt?ref=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .andExpect(method(org.springframework.http.HttpMethod.HEAD))
                .andRespond(withSuccess().header("X-Gitlab-Size", "408000"));

        long size = client.headFileSize(10L, "a.txt", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        assertThat(size).isEqualTo(408000L);
    }

    @Test
    void headFileSizeFailsClosedWhenHeaderMissing() {
        diffMockServer.expect(requestTo(
                        BASE_URL + "/projects/10/repository/files/a.txt?ref=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> client.headFileSize(10L, "a.txt", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .isInstanceOf(DiffIntegrityException.class)
                .extracting(ex -> ((DiffIntegrityException) ex).reason())
                .isEqualTo(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED);
    }

    @Test
    void headFileSizeFailsClosedOnNon2xx() {
        diffMockServer.expect(requestTo(
                        BASE_URL + "/projects/10/repository/files/a.txt?ref=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.headFileSize(10L, "a.txt", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .isInstanceOf(DiffIntegrityException.class);
    }

    @Test
    void headFileSizeRejectsAMalformedRefWithoutIssuingAnyRequest() {
        assertThatThrownBy(() -> client.headFileSize(10L, "a.txt", "not-a-sha"))
                .isInstanceOf(DiffIntegrityException.class);
        diffMockServer.verify();
    }

    // ---- listRecentNotes (WHT-20/§4.4: never throws) ----

    @Test
    void listRecentNotesReturnsAuthorAndBody() {
        diffMockServer.expect(requestTo(BASE_URL
                        + "/projects/10/merge_requests/5/notes?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"author": {"id": 35}, "body": "hello"}]
                        """, MediaType.APPLICATION_JSON));

        List<GitLabClient.Note> notes = client.listRecentNotes(10L, 5L);

        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).authorId()).isEqualTo(35L);
        assertThat(notes.get(0).body()).isEqualTo("hello");
    }

    @Test
    void listRecentNotesReturnsEmptyOnFailureRatherThanThrowing() {
        diffMockServer.expect(requestTo(BASE_URL
                        + "/projects/10/merge_requests/5/notes?per_page=100&page=1"))
                .andRespond(withServerError());

        List<GitLabClient.Note> notes = client.listRecentNotes(10L, 5L);

        assertThat(notes).isEmpty();
    }

    // ---- listOpenMergeRequestsForReviewer (WHR-18/23) ----

    @Test
    void listOpenMergeRequestsForReviewerFollowsPaginationUpToTheBound() {
        diffMockServer.expect(requestTo(BASE_URL
                        + "/merge_requests?reviewer_username=ai-review-bot&state=opened&scope=all&per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"project_id": 10, "iid": 5, "sha": "aaa"}]
                        """, MediaType.APPLICATION_JSON).header("X-Next-Page", "2"));
        diffMockServer.expect(requestTo(BASE_URL
                        + "/merge_requests?reviewer_username=ai-review-bot&state=opened&scope=all&per_page=100&page=2"))
                .andRespond(withSuccess("""
                        [{"project_id": 11, "iid": 6, "sha": "bbb"}]
                        """, MediaType.APPLICATION_JSON));

        List<GitLabClient.MergeRequestRef> refs =
                client.listOpenMergeRequestsForReviewer("ai-review-bot", null, 5, 100);

        assertThat(refs).hasSize(2);
        assertThat(refs.get(0).projectId()).isEqualTo(10L);
        assertThat(refs.get(1).projectId()).isEqualTo(11L);
    }

    @Test
    void listOpenMergeRequestsForReviewerStopsAtThePageCapEvenIfMorePagesExist() {
        diffMockServer.expect(requestTo(BASE_URL
                        + "/merge_requests?reviewer_username=ai-review-bot&state=opened&scope=all&per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"project_id": 10, "iid": 5, "sha": "aaa"}]
                        """, MediaType.APPLICATION_JSON).header("X-Next-Page", "2"));

        List<GitLabClient.MergeRequestRef> refs =
                client.listOpenMergeRequestsForReviewer("ai-review-bot", null, 1, 100);

        assertThat(refs).hasSize(1);
        diffMockServer.verify(); // only one page was ever requested
    }
}

package com.review.gateway.service;

import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.exception.PromptSourceInvalidException;
import com.review.gateway.exception.PromptSourceUnavailableException;
import com.review.gateway.service.dto.DiffPosition;
import com.review.gateway.service.dto.DiffRefs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;

class GitLabClientImplTest {

    private static final String BASE_URL = "https://gitlab.example.test/api/v4";
    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    private static final String SHA_C = "c".repeat(40);

    private MockRestServiceServer mockServer;
    private MockRestServiceServer promptMockServer;
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

        client = new GitLabClientImpl(builder.build(), promptBuilder.build(), new TextSanitizer(), new MetricsCounters());
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

        String discussionId = client.postDiscussion(10L, 5L, "a sanitized comment body", null);

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

        client.postDiscussion(999999L, 123456L, "body", null);

        mockServer.verify();
    }

    @Test
    void serverErrorIsTranslatedToGitLabPublishException() {
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.postDiscussion(1L, 1L, "body", null))
                .isInstanceOf(GitLabPublishException.class);
        mockServer.verify();
    }

    @Test
    void responseWithoutAnIdIsTreatedAsAFailure() {
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.postDiscussion(1L, 1L, "body", null))
                .isInstanceOf(GitLabPublishException.class);
    }

    // ---- postDiscussion wire shape (Diff Position Anchoring, DPR-03/DPR-08) ----

    @Test
    void fallbackBodyWithNoPositionSerializesToExactlyBodyNoPositionKey() {
        // DPR-03(a): asserted on serialized bytes, not object state -- byte-identical to today's output.
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andExpect(content().json("{\"body\":\"plain note\"}", true))
                .andRespond(withSuccess("{\"id\": \"d-1\"}", MediaType.APPLICATION_JSON));

        client.postDiscussion(1L, 1L, "plain note", null);

        mockServer.verify();
    }

    @Test
    void positionedBodyContainsExactlyTheLegalFieldsForAnAddedLine() {
        // DPR-03(b)/(c): added line -> new_line only, old_line omitted entirely (not null).
        DiffPosition position = new DiffPosition(SHA_A, SHA_B, SHA_C, "src/A.java", "src/A.java", null, 42);
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andExpect(content().json("""
                        {"body":"finding","position":{"position_type":"text","base_sha":"%s","start_sha":"%s",
                        "head_sha":"%s","old_path":"src/A.java","new_path":"src/A.java","new_line":42}}
                        """.formatted(SHA_A, SHA_B, SHA_C), true))
                .andRespond(withSuccess("{\"id\": \"d-1\"}", MediaType.APPLICATION_JSON));

        client.postDiscussion(1L, 1L, "finding", position);

        mockServer.verify();
    }

    @Test
    void positionedBodyContainsBothLineNumbersForAContextLine() {
        DiffPosition position = new DiffPosition(SHA_A, SHA_B, SHA_C, "src/A.java", "src/A.java", 10, 11);
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andExpect(content().json("""
                        {"body":"finding","position":{"position_type":"text","base_sha":"%s","start_sha":"%s",
                        "head_sha":"%s","old_path":"src/A.java","new_path":"src/A.java","old_line":10,"new_line":11}}
                        """.formatted(SHA_A, SHA_B, SHA_C), true))
                .andRespond(withSuccess("{\"id\": \"d-1\"}", MediaType.APPLICATION_JSON));

        client.postDiscussion(1L, 1L, "finding", position);

        mockServer.verify();
    }

    /**
     * DPR-03: the "no position key at all" contract must hold independent of any global Jackson
     * defaults a deployment might configure — {@code @JsonInclude(NON_NULL)} on the record itself
     * (asserted directly above, on serialized bytes through the real production code path) is what
     * guarantees this, not any property-inclusion default of the {@code RestClient}'s converter. This
     * project has no {@code spring.jackson.default-property-inclusion} override anywhere in
     * {@code application.yml} (verified), so the two round-trip assertions above are the meaningful,
     * non-fragile form of this check; a bespoke {@code ObjectMapper} reconstructed outside the real
     * request pipeline would only prove the annotation exists, which the round-trip tests already do.
     */

    @Test
    void badRequestWithPositionRetriesOnceWithoutPositionAndSucceeds() {
        // DPR-08: 400 -> retry ONCE with position omitted -> 200 succeeds; the retry body must carry no position.
        DiffPosition position = new DiffPosition(SHA_A, SHA_B, SHA_C, "A.java", "A.java", null, 1);
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andExpect(content().json("{\"body\":\"finding\",\"position\":{\"position_type\":\"text\","
                        + "\"base_sha\":\"" + SHA_A + "\",\"start_sha\":\"" + SHA_B + "\",\"head_sha\":\"" + SHA_C
                        + "\",\"old_path\":\"A.java\",\"new_path\":\"A.java\",\"new_line\":1}}", true))
                .andRespond(withBadRequest());
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andExpect(content().json("{\"body\":\"finding\"}", true))
                .andRespond(withSuccess("{\"id\": \"d-1\"}", MediaType.APPLICATION_JSON));

        String discussionId = client.postDiscussion(1L, 1L, "finding", position);

        assertThat(discussionId).isEqualTo("d-1");
        mockServer.verify();
    }

    @Test
    void badRequestThenBadRequestAgainSurfacesAsTransientExactlyOnceNoLoop() {
        DiffPosition position = new DiffPosition(SHA_A, SHA_B, SHA_C, "A.java", "A.java", null, 1);
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andRespond(withBadRequest());
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> client.postDiscussion(1L, 1L, "finding", position))
                .isInstanceOf(GitLabPublishException.class);
        mockServer.verify(); // exactly two requests were expected/consumed -- no further retry loop
    }

    @Test
    void badRequestWithNoPositionAttachedNeverRetries() {
        // DPR-08: the retry is gated on "a position was attached to the first attempt" -- a fallback
        // POST that already has no position must not retry (there is nothing left to omit).
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> client.postDiscussion(1L, 1L, "finding", null))
                .isInstanceOf(GitLabPublishException.class);
        mockServer.verify(); // exactly one request
    }

    @Test
    void tooManyRequestsDoesNotTriggerThePositionRetry() {
        // DPR-08: 429 must NOT be treated like 400 -- it keeps today's transient-failure path verbatim.
        DiffPosition position = new DiffPosition(SHA_A, SHA_B, SHA_C, "A.java", "A.java", null, 1);
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/1/discussions"))
                .andRespond(withTooManyRequests());

        assertThatThrownBy(() -> client.postDiscussion(1L, 1L, "finding", position))
                .isInstanceOf(GitLabPublishException.class);
        mockServer.verify(); // exactly one request -- no retry attempted for 429
    }

    // ---- fetchDiffRefs (Diff Position Anchoring, DPR-07) ----

    @Test
    void fetchDiffRefsHappyPathReturnsAllThreeShas() {
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/5"))
                .andExpect(method(GET))
                .andExpect(header("PRIVATE-TOKEN", "test-gitlab-token-0123456789012345"))
                .andRespond(withSuccess("""
                        {"id": 999, "title": "ignored free text", "diff_refs": {"base_sha": "%s",
                        "start_sha": "%s", "head_sha": "%s"}}
                        """.formatted(SHA_A, SHA_B, SHA_C), MediaType.APPLICATION_JSON));

        Optional<DiffRefs> refs = client.fetchDiffRefs(1L, 5L);

        assertThat(refs).contains(new DiffRefs(SHA_A, SHA_B, SHA_C));
        mockServer.verify();
    }

    @Test
    void fetchDiffRefsEmptyBodyIsEmpty() {
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/5"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.fetchDiffRefs(1L, 5L)).isEmpty();
    }

    @Test
    void fetchDiffRefsNullDiffRefsIsEmpty() {
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/5"))
                .andRespond(withSuccess("{\"diff_refs\": null}", MediaType.APPLICATION_JSON));

        assertThat(client.fetchDiffRefs(1L, 5L)).isEmpty();
    }

    @Test
    void fetchDiffRefsOneNullMemberIsEmptyNeverPartial() {
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/5"))
                .andRespond(withSuccess("""
                        {"diff_refs": {"base_sha": null, "start_sha": "%s", "head_sha": "%s"}}
                        """.formatted(SHA_B, SHA_C), MediaType.APPLICATION_JSON));

        assertThat(client.fetchDiffRefs(1L, 5L)).isEmpty();
    }

    @Test
    void fetchDiffRefsA39CharacterShaIsEmpty() {
        String shortSha = SHA_A.substring(0, 39);
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/5"))
                .andRespond(withSuccess("""
                        {"diff_refs": {"base_sha": "%s", "start_sha": "%s", "head_sha": "%s"}}
                        """.formatted(shortSha, SHA_B, SHA_C), MediaType.APPLICATION_JSON));

        assertThat(client.fetchDiffRefs(1L, 5L)).isEmpty();
    }

    @Test
    void fetchDiffRefsNonJsonBodyIsEmpty() {
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/5"))
                .andRespond(withSuccess("not json at all", MediaType.APPLICATION_JSON));

        assertThat(client.fetchDiffRefs(1L, 5L)).isEmpty();
    }

    @Test
    void fetchDiffRefsServerErrorIsEmpty() {
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/5"))
                .andRespond(withServerError());

        assertThat(client.fetchDiffRefs(1L, 5L)).isEmpty();
    }

    @Test
    void fetchDiffRefsConnectionResetIsEmpty() {
        mockServer.expect(requestTo(BASE_URL + "/projects/1/merge_requests/5"))
                .andRespond(request -> {
                    throw new java.io.IOException("Connection reset");
                });

        assertThat(client.fetchDiffRefs(1L, 5L)).isEmpty();
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
}

package com.review.gateway.service;

import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.exception.PromptSourceInvalidException;
import com.review.gateway.exception.PromptSourceUnavailableException;
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

        client = new GitLabClientImpl(builder.build(), promptBuilder.build());
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

    // ---- fetchRawFile (PMR-13/PMR-17) ----

    @Test
    void fetchRawFileReturnsDecodedContentOnSuccess() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/prompts%2Fbase.md/raw?ref=abc123"))
                .andExpect(method(GET))
                .andRespond(withSuccess("hello prompt content", MediaType.TEXT_PLAIN));

        Optional<String> content = client.fetchRawFile("42", "prompts/base.md", "abc123", 1000);

        assertThat(content).contains("hello prompt content");
    }

    @Test
    void fetchRawFile404ReturnsEmpty() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/missing.md/raw?ref=abc123"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        Optional<String> content = client.fetchRawFile("42", "missing.md", "abc123", 1000);

        assertThat(content).isEmpty();
    }

    @Test
    void fetchRawFileServerErrorIsUnavailable() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/x.md/raw?ref=abc123"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchRawFile("42", "x.md", "abc123", 1000))
                .isInstanceOf(PromptSourceUnavailableException.class);
    }

    @Test
    void fetchRawFileOversizedByContentLengthIsRejectedWithoutReadingBody() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/big.md/raw?ref=abc123"))
                .andRespond(withSuccess("x".repeat(50), MediaType.TEXT_PLAIN)
                        .header("Content-Length", "50"));

        assertThatThrownBy(() -> client.fetchRawFile("42", "big.md", "abc123", 10))
                .isInstanceOf(PromptSourceInvalidException.class);
    }

    @Test
    void fetchRawFileOversizedByStreamingIsRejectedEvenWithoutContentLengthHeader() {
        // PMR-17: the bound must be enforced while reading, not just via Content-Length.
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/big.md/raw?ref=abc123"))
                .andRespond(withSuccess("x".repeat(50), MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.fetchRawFile("42", "big.md", "abc123", 10))
                .isInstanceOf(PromptSourceInvalidException.class);
    }

    @Test
    void fetchRawFileEmptyBodyIsInvalid() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/empty.md/raw?ref=abc123"))
                .andRespond(withSuccess("", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.fetchRawFile("42", "empty.md", "abc123", 1000))
                .isInstanceOf(PromptSourceInvalidException.class);
    }

    @Test
    void fetchRawFileContainingNulByteIsInvalid() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/nul.md/raw?ref=abc123"))
                .andRespond(withSuccess("hello\u0000world", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.fetchRawFile("42", "nul.md", "abc123", 1000))
                .isInstanceOf(PromptSourceInvalidException.class);
    }

    @Test
    void fetchRawFileNestedPathIsUrlEncodedAsOneOpaqueSegment() {
        promptMockServer.expect(requestTo(
                        BASE_URL + "/projects/42/repository/files/.ai-review%2Fcode-rules.md/raw?ref=abc123"))
                .andRespond(withSuccess("rules content", MediaType.TEXT_PLAIN));

        Optional<String> content = client.fetchRawFile("42", ".ai-review/code-rules.md", "abc123", 1000);

        assertThat(content).contains("rules content");
    }
}

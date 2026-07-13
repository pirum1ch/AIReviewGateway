package com.review.gateway.service;

import com.review.gateway.exception.GitLabPublishException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class GitLabClientImplTest {

    private static final String BASE_URL = "https://gitlab.example.test/api/v4";

    private MockRestServiceServer mockServer;
    private GitLabClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("PRIVATE-TOKEN", "test-gitlab-token-0123456789012345");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new GitLabClientImpl(builder.build());
    }

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
}

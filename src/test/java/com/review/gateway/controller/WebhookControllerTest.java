package com.review.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.gateway.dto.GitLabWebhookPayload;
import com.review.gateway.service.WebhookReviewTriggerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * WHR-03/WHR-07: {@code WebhookController} extracts only {@code (projectId, mrIid, lastCommitHint)} from
 * the body and always responds the same coarse way, regardless of what {@link WebhookReviewTriggerService}
 * does downstream (it never inspects the outcome or lets an exception propagate here in shipped code).
 */
class WebhookControllerTest {

    private WebhookReviewTriggerService triggerService;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        triggerService = mock(WebhookReviewTriggerService.class);
        controller = new WebhookController(triggerService);
    }

    @Test
    void aRecognizedMergeRequestEventDelegatesWithOnlyProjectIdAndIid() {
        GitLabWebhookPayload payload = new GitLabWebhookPayload("merge_request",
                new GitLabWebhookPayload.Project(10L),
                new GitLabWebhookPayload.ObjectAttributes(5L, new GitLabWebhookPayload.LastCommit("abc123")));

        var response = controller.handleWebhook(payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(triggerService).handle(eq(10L), eq(5L), eq("abc123"));
    }

    @Test
    void aNonMergeRequestEventIsIgnoredButStillCoarselyAccepted() {
        GitLabWebhookPayload payload = new GitLabWebhookPayload("push",
                new GitLabWebhookPayload.Project(10L), null);

        var response = controller.handleWebhook(payload);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verifyNoInteractions(triggerService);
    }

    @Test
    void aMissingBodyIsIgnoredButStillCoarselyAccepted() {
        var response = controller.handleWebhook(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verifyNoInteractions(triggerService);
    }

    @Test
    void aMergeRequestEventMissingProjectOrIidIsIgnored() {
        GitLabWebhookPayload noProject = new GitLabWebhookPayload("merge_request", null,
                new GitLabWebhookPayload.ObjectAttributes(5L, null));
        GitLabWebhookPayload noIid = new GitLabWebhookPayload("merge_request",
                new GitLabWebhookPayload.Project(10L), new GitLabWebhookPayload.ObjectAttributes(null, null));

        controller.handleWebhook(noProject);
        controller.handleWebhook(noIid);

        verify(triggerService, never()).handle(any(), any(), any());
    }

    @Test
    void deserializesTheEmpiricallyConfirmedGitLabPayloadShape() throws Exception {
        String json = """
                {
                  "object_kind": "merge_request",
                  "event_type": "merge_request",
                  "project": {"id": 10, "name": "some-project"},
                  "object_attributes": {
                    "iid": 5,
                    "action": "update",
                    "reviewer_ids": [35, 7],
                    "last_commit": {"id": "deadbeef"}
                  },
                  "changes": {"reviewers": {"previous": [], "current": [{"id": 35}]}}
                }
                """;

        GitLabWebhookPayload payload = new ObjectMapper().readValue(json, GitLabWebhookPayload.class);

        assertThat(payload.isMergeRequestEvent()).isTrue();
        assertThat(payload.projectId()).isEqualTo(10L);
        assertThat(payload.mergeRequestIid()).isEqualTo(5L);
        assertThat(payload.lastCommitHint()).isEqualTo("deadbeef");
    }
}

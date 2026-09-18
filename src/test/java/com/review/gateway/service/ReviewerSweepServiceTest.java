package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffFetchUnavailableException;
import com.review.gateway.repository.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GitLab Webhook Diff Trigger — {@code ReviewerSweepService} (threat model WHR-23/WHR-24/WHT-17):
 * hourly backstop delegating to the same orchestrator the webhook uses, bounded per tick, never
 * overlapping itself.
 */
class ReviewerSweepServiceTest {

    private GatewayProperties properties;
    private GitLabClient gitLabClient;
    private ReviewRepository reviewRepository;
    private WebhookReviewTriggerService triggerService;
    private ReviewerSweepService sweepService;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.getWebhook().setEnabled(true);
        properties.getWebhook().setBotUsername("ai-review-bot");
        properties.getWebhook().getSweep().setMaxDiagnosticCommentsPerTick(2);

        gitLabClient = mock(GitLabClient.class);
        reviewRepository = mock(ReviewRepository.class);
        triggerService = mock(WebhookReviewTriggerService.class);
        sweepService = new ReviewerSweepService(properties, gitLabClient, reviewRepository, triggerService);
    }

    @Test
    void disabledFeatureIsANoOpWithoutAnyGitLabCall() {
        properties.getWebhook().setEnabled(false);

        int attempted = sweepService.sweep();

        assertThat(attempted).isZero();
        verifyNoInteractions(gitLabClient);
        verifyNoInteractions(triggerService);
    }

    @Test
    void candidatesAlreadyReviewedAtTheirListedShaAreSkippedWithoutDelegating() {
        GitLabClient.MergeRequestRef ref = new GitLabClient.MergeRequestRef(10L, 5L, "a".repeat(40));
        when(gitLabClient.listOpenMergeRequestsForReviewer(anyString(), any(Instant.class), anyInt(), anyInt()))
                .thenReturn(List.of(ref));
        when(reviewRepository.existsByProjectIdAndMergeRequestIdAndHeadSha(10L, 5L, ref.sha())).thenReturn(true);

        int attempted = sweepService.sweep();

        assertThat(attempted).isZero();
        verify(triggerService, never()).handle(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void newCandidatesAreDelegatedToTheSharedTriggerService() {
        GitLabClient.MergeRequestRef ref1 = new GitLabClient.MergeRequestRef(10L, 5L, "a".repeat(40));
        GitLabClient.MergeRequestRef ref2 = new GitLabClient.MergeRequestRef(11L, 6L, null); // no sha hint at all
        when(gitLabClient.listOpenMergeRequestsForReviewer(anyString(), any(Instant.class), anyInt(), anyInt()))
                .thenReturn(List.of(ref1, ref2));
        when(reviewRepository.existsByProjectIdAndMergeRequestIdAndHeadSha(10L, 5L, ref1.sha())).thenReturn(false);

        int attempted = sweepService.sweep();

        assertThat(attempted).isEqualTo(2);
        verify(triggerService).handle(10L, 5L, ref1.sha(), true);
        verify(triggerService).handle(11L, 6L, null, true);
    }

    @Test
    void diagnosticCommentBudgetIsCappedPerTickButEveryCandidateIsStillAttempted() {
        List<GitLabClient.MergeRequestRef> refs = List.of(
                new GitLabClient.MergeRequestRef(1L, 1L, null),
                new GitLabClient.MergeRequestRef(2L, 2L, null),
                new GitLabClient.MergeRequestRef(3L, 3L, null));
        when(gitLabClient.listOpenMergeRequestsForReviewer(anyString(), any(Instant.class), anyInt(), anyInt()))
                .thenReturn(refs);

        int attempted = sweepService.sweep();

        assertThat(attempted).isEqualTo(3);
        verify(triggerService).handle(1L, 1L, null, true);
        verify(triggerService).handle(2L, 2L, null, true);
        verify(triggerService).handle(3L, 3L, null, false); // budget of 2 exhausted by the first two
    }

    /**
     * F-WH-02: {@code WebhookReviewTriggerService.handle} already has its own catch-all backstop, but
     * this defends in depth -- a candidate that somehow still throws must not abort the rest of the tick
     * (the {@link com.review.gateway.service.PublishRetryService} per-item pattern).
     */
    @Test
    void aCandidateThatThrowsDoesNotAbortTheRestOfTheTick() {
        List<GitLabClient.MergeRequestRef> refs = List.of(
                new GitLabClient.MergeRequestRef(1L, 1L, null),
                new GitLabClient.MergeRequestRef(2L, 2L, null),
                new GitLabClient.MergeRequestRef(3L, 3L, null));
        when(gitLabClient.listOpenMergeRequestsForReviewer(anyString(), any(Instant.class), anyInt(), anyInt()))
                .thenReturn(refs);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(triggerService).handle(2L, 2L, null, true);

        int attempted = sweepService.sweep();

        assertThat(attempted).isEqualTo(3);
        verify(triggerService).handle(1L, 1L, null, true);
        verify(triggerService).handle(2L, 2L, null, true);
        verify(triggerService).handle(3L, 3L, null, false); // budget of 2 (setUp) exhausted by the first two
    }

    @Test
    void aTransientListingFailureIsALoggedNoOp() {
        when(gitLabClient.listOpenMergeRequestsForReviewer(anyString(), any(Instant.class), anyInt(), anyInt()))
                .thenThrow(new DiffFetchUnavailableException("network blip"));

        int attempted = sweepService.sweep();

        assertThat(attempted).isZero();
        verifyNoInteractions(triggerService);
    }

    @Test
    void aTickInProgressIsSkippedRatherThanOverlapping() throws InterruptedException {
        when(gitLabClient.listOpenMergeRequestsForReviewer(anyString(), any(Instant.class), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    // A concurrent sweep() call while this one is still "running" must be skipped, not queued.
                    Thread nested = new Thread(() -> sweepService.sweep());
                    nested.start();
                    nested.join();
                    return List.of();
                });

        sweepService.sweep();

        verify(gitLabClient, times(1)).listOpenMergeRequestsForReviewer(anyString(), any(Instant.class), anyInt(), anyInt());
    }
}

package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffFetchUnavailableException;
import com.review.gateway.exception.DiffIntegrityException;
import com.review.gateway.exception.DiffTooLargeException;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.CreateReviewCommand;
import com.review.gateway.service.dto.CreateReviewResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebhookReviewTriggerServiceTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long MR_IID = 5L;
    private static final Long BOT_USER_ID = 35L;
    private static final String BASE_SHA = "a".repeat(40);
    private static final String HEAD_SHA = "b".repeat(40);

    private GatewayProperties properties;
    private GitLabClient gitLabClient;
    private DiffIntegrityVerifier diffIntegrityVerifier;
    private DiffAssembler diffAssembler;
    private ReviewService reviewService;
    private ReviewRepository reviewRepository;
    private MetricsCounters metricsCounters;
    private WebhookReviewTriggerService service;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.getWebhook().setEnabled(true);
        properties.getWebhook().setBotUserId(BOT_USER_ID);
        properties.getWebhook().setPromptVersion("v2");
        properties.getWebhook().setMaxReviewsPerProjectPerHour(20);
        properties.getWebhook().setMaxReviewsPerHour(100);
        properties.getWebhook().setMaxConcurrentFetches(4);

        gitLabClient = mock(GitLabClient.class);
        diffIntegrityVerifier = mock(DiffIntegrityVerifier.class);
        diffAssembler = mock(DiffAssembler.class);
        reviewService = mock(ReviewService.class);
        reviewRepository = mock(ReviewRepository.class);
        metricsCounters = mock(MetricsCounters.class);

        service = new WebhookReviewTriggerService(properties, gitLabClient, diffIntegrityVerifier, diffAssembler,
                reviewService, reviewRepository, metricsCounters);

        when(reviewService.createReview(any())).thenReturn(new CreateReviewResult(1L, ReviewStatus.QUEUED, false, 1));
    }

    private GitLabClient.MergeRequestSnapshot openMrWithBotAsReviewer() {
        return new GitLabClient.MergeRequestSnapshot("opened", BASE_SHA, HEAD_SHA, List.of(BOT_USER_ID, 7L));
    }

    @Test
    void happyPathFetchesVerifiesAssemblesAndCreates() {
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID)).thenReturn(openMrWithBotAsReviewer());
        List<GitLabClient.DiffEntry> entries = List.of(
                new GitLabClient.DiffEntry("a.txt", "a.txt", "100644", "100644", false, false, false, "@@ -1 +1 @@\n-x\n+y\n"));
        when(diffIntegrityVerifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false)).thenReturn(entries);
        when(diffAssembler.assemble(entries)).thenReturn("diff --git a/a.txt b/a.txt\n...");

        service.handle(PROJECT_ID, MR_IID, null);

        verify(reviewService).createReview(any(CreateReviewCommand.class));
    }

    @Test
    void ignoresProjectNotOnTheAllowlistWithoutAnyGitLabCall() {
        Set<Long> allowlist = new LinkedHashSet<>();
        allowlist.add(999L);
        properties.getWebhook().setAllowedProjectIds(allowlist);

        service.handle(PROJECT_ID, MR_IID, null);

        verifyNoInteractions(gitLabClient);
        verify(reviewService, never()).createReview(any());
    }

    @Test
    void fastPathDedupSkipsAllGitLabCallsWhenHintMatchesAnExistingReview() {
        when(reviewRepository.existsByProjectIdAndMergeRequestIdAndHeadSha(PROJECT_ID, MR_IID, HEAD_SHA)).thenReturn(true);

        service.handle(PROJECT_ID, MR_IID, HEAD_SHA);

        verifyNoInteractions(gitLabClient);
        verify(reviewService, never()).createReview(any());
    }

    @Test
    void botNotAReviewerIsACoarseNoOp() {
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID))
                .thenReturn(new GitLabClient.MergeRequestSnapshot("opened", BASE_SHA, HEAD_SHA, List.of(7L, 8L)));

        service.handle(PROJECT_ID, MR_IID, null);

        verify(reviewService, never()).createReview(any());
    }

    @Test
    void closedMrIsACoarseNoOp() {
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID))
                .thenReturn(new GitLabClient.MergeRequestSnapshot("closed", BASE_SHA, HEAD_SHA, List.of(BOT_USER_ID)));

        service.handle(PROJECT_ID, MR_IID, null);

        verify(reviewService, never()).createReview(any());
    }

    @Test
    void transientFetchFailureCreatesNoReviewAndPostsNoComment() {
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID))
                .thenThrow(new DiffFetchUnavailableException("network blip"));

        service.handle(PROJECT_ID, MR_IID, null);

        verify(reviewService, never()).createReview(any());
        verify(gitLabClient, never()).postDiscussion(anyLong(), anyLong(), anyString());
    }

    @Test
    void integrityFailureLogsMetricAndPostsADiagnosticCommentOnce() {
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID)).thenReturn(openMrWithBotAsReviewer());
        when(diffIntegrityVerifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .thenThrow(new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_TOO_LARGE_OR_TRUNCATED, "boom"));
        when(gitLabClient.listRecentNotes(PROJECT_ID, MR_IID)).thenReturn(Optional.of(List.of()));

        service.handle(PROJECT_ID, MR_IID, null);

        verify(reviewService, never()).createReview(any());
        verify(metricsCounters).incrementWebhookDiffIntegrityFailure("DIFF_TOO_LARGE_OR_TRUNCATED");
        verify(gitLabClient, times(1)).postDiscussion(eq(PROJECT_ID), eq(MR_IID), anyString());
    }

    @Test
    void integrityFailureDoesNotDuplicateAnAlreadyPostedComment() {
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID)).thenReturn(openMrWithBotAsReviewer());
        when(diffIntegrityVerifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .thenThrow(new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNAVAILABLE, "boom"));
        String alreadyPostedBody = DiagnosticCommentRenderer.render(DiffIntegrityException.Reason.DIFF_UNAVAILABLE, HEAD_SHA);
        when(gitLabClient.listRecentNotes(PROJECT_ID, MR_IID))
                .thenReturn(Optional.of(List.of(new GitLabClient.Note(BOT_USER_ID, alreadyPostedBody))));

        service.handle(PROJECT_ID, MR_IID, null);

        verify(gitLabClient, never()).postDiscussion(anyLong(), anyLong(), anyString());
        // WHR-28: the authoritative signal still fires even though the courtesy comment is suppressed.
        verify(metricsCounters).incrementWebhookDiffIntegrityFailure("DIFF_UNAVAILABLE");
    }

    /** F-WH-03: an unreadable/ambiguous notes response must suppress the courtesy comment, not post it. */
    @Test
    void unreadableNotesResponseSuppressesTheCommentRatherThanPosting() {
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID)).thenReturn(openMrWithBotAsReviewer());
        when(diffIntegrityVerifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .thenThrow(new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNAVAILABLE, "boom"));
        when(gitLabClient.listRecentNotes(PROJECT_ID, MR_IID)).thenReturn(Optional.empty());

        service.handle(PROJECT_ID, MR_IID, null);

        verify(gitLabClient, never()).postDiscussion(anyLong(), anyLong(), anyString());
        // WHR-28: the authoritative signal still fires even though the courtesy comment is suppressed.
        verify(metricsCounters).incrementWebhookDiffIntegrityFailure("DIFF_UNAVAILABLE");
    }

    @Test
    void aProjectMemberNoteMimickingTheMarkerDoesNotSuppressTheComment() {
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID)).thenReturn(openMrWithBotAsReviewer());
        when(diffIntegrityVerifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .thenThrow(new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNAVAILABLE, "boom"));
        String spoofedBody = DiagnosticCommentRenderer.render(DiffIntegrityException.Reason.DIFF_UNAVAILABLE, HEAD_SHA);
        // Same marker text, but authored by a DIFFERENT user id -- must not suppress.
        when(gitLabClient.listRecentNotes(PROJECT_ID, MR_IID))
                .thenReturn(Optional.of(List.of(new GitLabClient.Note(999L, spoofedBody))));

        service.handle(PROJECT_ID, MR_IID, null);

        verify(gitLabClient, times(1)).postDiscussion(eq(PROJECT_ID), eq(MR_IID), anyString());
    }

    @Test
    void diagnosticCommentIsSuppressedWhenNotAllowedBySweepButMetricStillFires() {
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID)).thenReturn(openMrWithBotAsReviewer());
        when(diffIntegrityVerifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false))
                .thenThrow(new DiffIntegrityException(DiffIntegrityException.Reason.DIFF_UNSUPPORTED_CONTENT, "boom"));

        service.handle(PROJECT_ID, MR_IID, null, false);

        verify(gitLabClient, never()).listRecentNotes(anyLong(), anyLong());
        verify(gitLabClient, never()).postDiscussion(anyLong(), anyLong(), anyString());
        verify(metricsCounters).incrementWebhookDiffIntegrityFailure("DIFF_UNSUPPORTED_CONTENT");
    }

    @Test
    void rateLimitSuppressesFurtherCreationsOnceTheProjectBucketIsExhausted() {
        properties.getWebhook().setMaxReviewsPerProjectPerHour(1);
        when(gitLabClient.fetchMergeRequest(eq(PROJECT_ID), any())).thenReturn(openMrWithBotAsReviewer());
        List<GitLabClient.DiffEntry> entries = List.of(
                new GitLabClient.DiffEntry("a.txt", "a.txt", "100644", "100644", false, false, false, "@@ -1 +1 @@\n-x\n+y\n"));
        when(diffIntegrityVerifier.verify(eq(PROJECT_ID), any(), eq(BASE_SHA), eq(HEAD_SHA), eq(false))).thenReturn(entries);
        when(diffAssembler.assemble(entries)).thenReturn("diff --git a/a.txt b/a.txt\n...");

        service.handle(PROJECT_ID, 1L, null);
        service.handle(PROJECT_ID, 2L, null);

        verify(reviewService, times(1)).createReview(any());
        verify(metricsCounters, times(1)).incrementWebhookRateLimited();
    }

    @Test
    void aMissingOrNonPositiveProjectIdOrMrIidIsIgnored() {
        service.handle(null, MR_IID, null);
        service.handle(PROJECT_ID, null, null);
        service.handle(-1L, MR_IID, null);
        service.handle(PROJECT_ID, 0L, null);

        verifyNoInteractions(gitLabClient);
    }

    /**
     * F-WH-02: {@code createReview} can throw exception types {@code doHandle}'s two specific catches do
     * not enumerate (e.g. {@code DiffTooLargeException} for a legitimately-oversized assembled diff) --
     * the catch-all backstop must swallow it, never let it escape {@code handle}, so WHR-07's "never
     * throws" contract holds regardless of which exception type ReviewService grows next.
     */
    @Test
    void anUnenumeratedRuntimeExceptionFromCreateReviewIsSwallowedByTheBackstop() {
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID)).thenReturn(openMrWithBotAsReviewer());
        List<GitLabClient.DiffEntry> entries = List.of(
                new GitLabClient.DiffEntry("a.txt", "a.txt", "100644", "100644", false, false, false, "@@ -1 +1 @@\n-x\n+y\n"));
        when(diffIntegrityVerifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false)).thenReturn(entries);
        when(diffAssembler.assemble(entries)).thenReturn("diff --git a/a.txt b/a.txt\n...");
        when(reviewService.createReview(any())).thenThrow(new DiffTooLargeException("assembled diff too large"));

        assertThatCode(() -> service.handle(PROJECT_ID, MR_IID, null)).doesNotThrowAnyException();

        verify(metricsCounters).incrementWebhookUnexpectedFailure();
        verify(gitLabClient, never()).postDiscussion(anyLong(), anyLong(), anyString());
    }

    /**
     * F-WH-05: when every file in the diff is a sound WHR-15 exemption, {@code coverageBearing} comes
     * back empty -- there is nothing to review, so no Review row/Worker job should be created.
     */
    @Test
    void allFilesExemptCreatesNoReviewAndDoesNotAssemble() {
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID)).thenReturn(openMrWithBotAsReviewer());
        when(diffIntegrityVerifier.verify(PROJECT_ID, MR_IID, BASE_SHA, HEAD_SHA, false)).thenReturn(List.of());

        service.handle(PROJECT_ID, MR_IID, null);

        verify(reviewService, never()).createReview(any());
        verify(diffAssembler, never()).assemble(any());
    }
}

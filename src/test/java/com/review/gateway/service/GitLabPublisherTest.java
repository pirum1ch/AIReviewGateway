package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewComment;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.model.enums.Severity;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.DiffRefs;
import com.review.gateway.service.dto.PublishOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link GitLabPublisher} against a real (Zonky) database: idempotent re-publish (only
 * unpublished comments are sent), the OBSOLETE guard, and a transient-failure keeping the Review
 * COMPLETED (req. 1.10). Only {@link GitLabClient} — the true external HTTP boundary — is mocked.
 *
 * <p>{@code @Transactional(propagation = NOT_SUPPORTED)} disables {@code @DataJpaTest}'s default
 * per-test transaction wrapper for the same reason as {@code ResultProcessorTest}: {@link
 * GitLabPublisher} opens genuine {@code REQUIRES_NEW} transactions via {@code TransactionTemplate},
 * which would not see fixture rows only flushed inside a still-open ambient test transaction. Setup
 * goes through the repositories directly so every fixture row is actually committed first.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GitLabPublisherTest extends AbstractPostgresIntegrationTest {

    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);
    private static final String SHA_C = "c".repeat(40);

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewCommentRepository reviewCommentRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private ReviewInputRepository reviewInputRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * With {@code @Transactional(NOT_SUPPORTED)} disabling {@code @DataJpaTest}'s default per-test
     * rollback, rows committed here would otherwise persist in the (test-context-cached,
     * shared-across-test-classes) embedded database, polluting other tests' unscoped queries (e.g.
     * {@code ReviewRepositoryTest}'s exact-count/exact-list assertions over ALL rows). {@code reviews}
     * cascades to {@code review_comments}/{@code review_events} at the DB level (V1 migration,
     * {@code ON DELETE CASCADE}).
     */
    @AfterEach
    void cleanUpCommittedRows() {
        reviewRepository.deleteAll();
    }

    private GitLabPublisher newPublisher(GitLabClient gitLabClient) {
        return newPublisher(gitLabClient, new GatewayProperties(), new DiffPositionResolver());
    }

    /** Diff Position Anchoring tests: lets a test supply its own flag state and/or a stubbed resolver. */
    private GitLabPublisher newPublisher(GitLabClient gitLabClient, GatewayProperties properties,
                                          DiffPositionResolver diffPositionResolver) {
        return newPublisher(gitLabClient, properties, diffPositionResolver, new MetricsCounters());
    }

    /** F-DP-02: lets a test supply its own {@link MetricsCounters} instance so it can assert on it afterward. */
    private GitLabPublisher newPublisher(GitLabClient gitLabClient, GatewayProperties properties,
                                          DiffPositionResolver diffPositionResolver, MetricsCounters metricsCounters) {
        EventService eventService = new EventService(reviewEventRepository, new TextSanitizer());
        StateMachine stateMachine = new StateMachine(eventService);
        return new GitLabPublisher(reviewRepository, reviewCommentRepository, reviewInputRepository, stateMachine,
                gitLabClient, diffPositionResolver, properties, metricsCounters, transactionManager);
    }

    private Review persistReview(String headSha, ReviewStatus status) {
        Review review = new Review(1L, 1L, headSha, "base", "v1", 10);
        review.setStatus(status);
        return reviewRepository.saveAndFlush(review);
    }

    private ReviewComment persistComment(Review review, String text) {
        return reviewCommentRepository.saveAndFlush(new ReviewComment(review.getId(), "A.java", 1, Severity.MINOR, text));
    }

    @Test
    void publishesAllUnpublishedCommentsAndTransitionsToPublished() {
        Review review = persistReview("sha-pub-1", ReviewStatus.COMPLETED);
        persistComment(review, "finding one");
        persistComment(review, "finding two");

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenReturn("discussion-1", "discussion-2");

        GitLabPublisher publisher = newPublisher(gitLabClient);
        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.PUBLISHED);

        List<ReviewComment> comments = reviewCommentRepository.findByReviewId(review.getId());
        assertThat(comments).allSatisfy(c -> {
            assertThat(c.getPublishedAt()).isNotNull();
            assertThat(c.getDiscussionId()).isNotNull();
        });
    }

    @Test
    void reviewWithZeroCommentsStillTransitionsToPublished() {
        Review review = persistReview("sha-pub-empty", ReviewStatus.COMPLETED);

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        GitLabPublisher publisher = newPublisher(gitLabClient);

        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        assertThat(reviewRepository.findById(review.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        verify(gitLabClient, never()).postDiscussion(any(), any(), any(), any());
    }

    @Test
    void republishOnlySendsUnpublishedComments() {
        Review review = persistReview("sha-pub-2", ReviewStatus.COMPLETED);
        ReviewComment alreadyPublished = persistComment(review, "already done");
        alreadyPublished.setDiscussionId("existing-discussion");
        alreadyPublished.setPublishedAt(java.time.Instant.now());
        reviewCommentRepository.saveAndFlush(alreadyPublished);
        persistComment(review, "still pending");

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenReturn("new-discussion");

        GitLabPublisher publisher = newPublisher(gitLabClient);
        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        verify(gitLabClient, times(1)).postDiscussion(any(), any(), eq("still pending"), any());
        verify(gitLabClient, never()).postDiscussion(any(), any(), eq("already done"), any());
    }

    @Test
    void obsoleteReviewIsNotPublished() {
        Review review = persistReview("sha-pub-3", ReviewStatus.OBSOLETE);
        persistComment(review, "should never be published");

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        GitLabPublisher publisher = newPublisher(gitLabClient);

        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.NOT_APPLICABLE);
        verify(gitLabClient, never()).postDiscussion(any(), any(), any(), any());
        assertThat(reviewRepository.findById(review.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.OBSOLETE);
    }

    @Test
    void cancelledReviewIsNotPublished() {
        Review review = persistReview("sha-pub-4", ReviewStatus.CANCELLED);
        persistComment(review, "should never be published");

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        GitLabPublisher publisher = newPublisher(gitLabClient);

        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.NOT_APPLICABLE);
        verify(gitLabClient, never()).postDiscussion(any(), any(), any(), any());
    }

    @Test
    void transientGitLabFailureKeepsReviewCompletedForRetry() {
        Review review = persistReview("sha-pub-5", ReviewStatus.COMPLETED);
        persistComment(review, "will fail to publish");

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenThrow(new GitLabPublishException("GitLab unavailable"));

        GitLabPublisher publisher = newPublisher(gitLabClient);
        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PARTIAL);
        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.COMPLETED);

        List<ReviewComment> comments = reviewCommentRepository.findByReviewIdAndPublishedAtIsNull(review.getId());
        assertThat(comments).hasSize(1);
    }

    @Test
    void partialFailureStillPublishesTheSucceedingComments() {
        Review review = persistReview("sha-pub-6", ReviewStatus.COMPLETED);
        persistComment(review, "will succeed");
        persistComment(review, "will fail");

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        when(gitLabClient.postDiscussion(any(), any(), eq("will succeed"), any())).thenReturn("discussion-ok");
        when(gitLabClient.postDiscussion(any(), any(), eq("will fail"), any())).thenThrow(new GitLabPublishException("boom"));

        GitLabPublisher publisher = newPublisher(gitLabClient);
        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PARTIAL);
        assertThat(reviewRepository.findById(review.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.COMPLETED);

        List<ReviewComment> allComments = reviewCommentRepository.findByReviewId(review.getId());
        assertThat(allComments).anySatisfy(c -> {
            assertThat(c.getComment()).isEqualTo("will succeed");
            assertThat(c.getPublishedAt()).isNotNull();
        });
        assertThat(allComments).anySatisfy(c -> {
            assertThat(c.getComment()).isEqualTo("will fail");
            assertThat(c.getPublishedAt()).isNull();
        });
    }

    @Test
    void reviewNotYetCompletedIsNotPublished() {
        Review review = persistReview("sha-pub-7", ReviewStatus.RUNNING);

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        GitLabPublisher publisher = newPublisher(gitLabClient);

        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.NOT_APPLICABLE);
    }

    // ---- Diff Position Anchoring (DPR-01/DPR-06/DPR-11) ----

    @Test
    void positionResolverThrowingStillPublishesEveryCommentAsAPlainNoteAndReachesPublished() {
        // DPR-01: buildPositionContext's failure must never propagate out of publishReview -- a single
        // poisoned diff (e.g. a crafted hunk header) degrades this Review to plain notes, not FAILED.
        Review review = persistReview(SHA_A, ReviewStatus.COMPLETED);
        persistComment(review, "finding one");
        reviewInputRepository.saveAndFlush(new ReviewInput(review.getId(), "some diff", "v1", SHA_A, "base", 10));

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        when(gitLabClient.fetchDiffRefs(any(), any())).thenReturn(Optional.of(new DiffRefs(SHA_B, SHA_C, SHA_A)));
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenReturn("discussion-1");

        DiffPositionResolver throwingResolver = Mockito.mock(DiffPositionResolver.class);
        when(throwingResolver.resolve(any(), any())).thenThrow(new RuntimeException("boom: crafted hunk header"));

        GitLabPublisher publisher = newPublisher(gitLabClient, new GatewayProperties(), throwingResolver);
        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        verify(gitLabClient).postDiscussion(any(), any(), eq("finding one"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void flagOffMeansZeroFetchDiffRefsCallsAndStillPublishes() {
        // DPR-11: the flag is checked before any I/O.
        Review review = persistReview(SHA_A, ReviewStatus.COMPLETED);
        persistComment(review, "finding one");

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenReturn("discussion-1");

        GatewayProperties properties = new GatewayProperties();
        properties.getPublish().setPositionAnchoringEnabled(false);
        GitLabPublisher publisher = newPublisher(gitLabClient, properties, new DiffPositionResolver());

        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        verify(gitLabClient, never()).fetchDiffRefs(any(), any());
    }

    @Test
    void headShaMismatchFallsBackToPlainNotes() {
        // DPR-06: diff_refs.head_sha (SHA_B) does not equal review.headSha (SHA_A) -- stale MR state,
        // anchoring must be skipped, never a prefix/startsWith match.
        Review review = persistReview(SHA_A, ReviewStatus.COMPLETED);
        persistComment(review, "finding one");
        reviewInputRepository.saveAndFlush(new ReviewInput(review.getId(), "some diff", "v1", SHA_A, "base", 10));

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        when(gitLabClient.fetchDiffRefs(any(), any())).thenReturn(Optional.of(new DiffRefs(SHA_A, SHA_C, SHA_B)));
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenReturn("discussion-1");

        // F-DP-02: the head_sha-mismatch branch must move a counter -- pre-fix it was indistinguishable
        // on GET /metrics from the healthy "nothing needed anchoring" steady state.
        MetricsCounters metricsCounters = new MetricsCounters();
        GitLabPublisher publisher =
                newPublisher(gitLabClient, new GatewayProperties(), new DiffPositionResolver(), metricsCounters);
        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        verify(gitLabClient).fetchDiffRefs(review.getProjectId(), review.getMergeRequestId());
        verify(gitLabClient).postDiscussion(any(), any(), eq("finding one"), org.mockito.ArgumentMatchers.isNull());
        assertThat(metricsCounters.diffRefsUnavailableCount()).isEqualTo(1);
        assertThat(metricsCounters.positionsUnresolvedCount()).isZero();
        assertThat(metricsCounters.positionsAnchoredCount()).isZero();
    }

    @Test
    void totalResolutionMissMovesPositionsUnresolvedNotJustAPartialMiss() {
        // F-DP-02: previously, buildPositionContext returned null (no counter at all) whenever the
        // resolver resolved *nothing* for the Review, which is byte-identical on GET /metrics to "no
        // comment had a line number" -- undermining DPR-12's own stated purpose. A diff that matches
        // head_sha but shares no (file, line) with the comment must now still increment
        // positionsUnresolved for that comment.
        Review review = persistReview(SHA_A, ReviewStatus.COMPLETED);
        persistComment(review, "finding one"); // filePath="A.java", lineNumber=1
        String diff = "diff --git a/Other.java b/Other.java\n"
                + "--- a/Other.java\n"
                + "+++ b/Other.java\n"
                + "@@ -1,1 +1,1 @@\n"
                + "+unrelated line\n";
        reviewInputRepository.saveAndFlush(new ReviewInput(review.getId(), diff, "v1", SHA_A, "base", 10));

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        when(gitLabClient.fetchDiffRefs(any(), any())).thenReturn(Optional.of(new DiffRefs(SHA_B, SHA_C, SHA_A)));
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenReturn("discussion-1");

        MetricsCounters metricsCounters = new MetricsCounters();
        GitLabPublisher publisher =
                newPublisher(gitLabClient, new GatewayProperties(), new DiffPositionResolver(), metricsCounters);
        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        verify(gitLabClient).postDiscussion(any(), any(), eq("finding one"), org.mockito.ArgumentMatchers.isNull());
        assertThat(metricsCounters.positionsUnresolvedCount()).isEqualTo(1);
        assertThat(metricsCounters.positionsAnchoredCount()).isZero();
        assertThat(metricsCounters.diffRefsUnavailableCount()).isZero();
    }

    @Test
    void matchingHeadShaAndAResolvableLineAttachesAPosition() {
        Review review = persistReview(SHA_A, ReviewStatus.COMPLETED);
        persistComment(review, "finding one"); // filePath="A.java", lineNumber=1 (see persistComment)
        String diff = "diff --git a/A.java b/A.java\n"
                + "--- a/A.java\n"
                + "+++ b/A.java\n"
                + "@@ -1,1 +1,1 @@\n"
                + "+new line\n";
        reviewInputRepository.saveAndFlush(new ReviewInput(review.getId(), diff, "v1", SHA_A, "base", 10));

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        when(gitLabClient.fetchDiffRefs(any(), any())).thenReturn(Optional.of(new DiffRefs(SHA_B, SHA_C, SHA_A)));
        when(gitLabClient.postDiscussion(any(), any(), any(), any())).thenReturn("discussion-1");

        GitLabPublisher publisher = newPublisher(gitLabClient, new GatewayProperties(), new DiffPositionResolver());
        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        org.mockito.ArgumentCaptor<com.review.gateway.service.dto.DiffPosition> captor =
                org.mockito.ArgumentCaptor.forClass(com.review.gateway.service.dto.DiffPosition.class);
        verify(gitLabClient).postDiscussion(any(), any(), eq("finding one"), captor.capture());
        assertThat(captor.getValue()).isNotNull();
        assertThat(captor.getValue().newPath()).isEqualTo("A.java");
        assertThat(captor.getValue().newLine()).isEqualTo(1);
    }
}

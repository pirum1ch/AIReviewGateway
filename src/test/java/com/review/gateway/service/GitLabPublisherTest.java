package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewComment;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.model.enums.Severity;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.PublishOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewCommentRepository reviewCommentRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
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
        EventService eventService = new EventService(reviewEventRepository);
        StateMachine stateMachine = new StateMachine(eventService);
        return new GitLabPublisher(reviewRepository, reviewCommentRepository, stateMachine, gitLabClient, transactionManager);
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
        when(gitLabClient.postDiscussion(any(), any(), any())).thenReturn("discussion-1", "discussion-2");

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
        verify(gitLabClient, never()).postDiscussion(any(), any(), any());
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
        when(gitLabClient.postDiscussion(any(), any(), any())).thenReturn("new-discussion");

        GitLabPublisher publisher = newPublisher(gitLabClient);
        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.PUBLISHED);
        verify(gitLabClient, org.mockito.Mockito.times(1)).postDiscussion(any(), any(), eq("still pending"));
        verify(gitLabClient, never()).postDiscussion(any(), any(), eq("already done"));
    }

    @Test
    void obsoleteReviewIsNotPublished() {
        Review review = persistReview("sha-pub-3", ReviewStatus.OBSOLETE);
        persistComment(review, "should never be published");

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        GitLabPublisher publisher = newPublisher(gitLabClient);

        PublishOutcome outcome = publisher.publishReview(review.getId());

        assertThat(outcome).isEqualTo(PublishOutcome.NOT_APPLICABLE);
        verify(gitLabClient, never()).postDiscussion(any(), any(), any());
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
        verify(gitLabClient, never()).postDiscussion(any(), any(), any());
    }

    @Test
    void transientGitLabFailureKeepsReviewCompletedForRetry() {
        Review review = persistReview("sha-pub-5", ReviewStatus.COMPLETED);
        persistComment(review, "will fail to publish");

        GitLabClient gitLabClient = Mockito.mock(GitLabClient.class);
        when(gitLabClient.postDiscussion(any(), any(), any())).thenThrow(new GitLabPublishException("GitLab unavailable"));

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
        when(gitLabClient.postDiscussion(any(), any(), eq("will succeed"))).thenReturn("discussion-ok");
        when(gitLabClient.postDiscussion(any(), any(), eq("will fail"))).thenThrow(new GitLabPublishException("boom"));

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
}

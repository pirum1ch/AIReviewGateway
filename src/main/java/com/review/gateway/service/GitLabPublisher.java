package com.review.gateway.service;

import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.exception.ReviewNotFoundException;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewComment;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.PublishOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Publishes a COMPLETED Review's unpublished comments to GitLab (req. 1.10). Each comment is posted
 * and marked published in its own {@code REQUIRES_NEW} transaction (via {@link TransactionTemplate} —
 * see {@code ReviewService} javadoc for why self-invoked {@code @Transactional} would not work here),
 * so a failure publishing comment N never rolls back the successful publish of comments 1..N-1
 * (idempotency, architecture §6). Only once every comment is published does the Review transition
 * {@code COMPLETED -> PUBLISHED}; a transient GitLab failure leaves it {@code COMPLETED} for
 * {@code PublishRetryService} to retry later.
 */
@Service
public class GitLabPublisher {

    private static final Logger log = LoggerFactory.getLogger(GitLabPublisher.class);

    private final ReviewRepository reviewRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final StateMachine stateMachine;
    private final GitLabClient gitLabClient;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public GitLabPublisher(ReviewRepository reviewRepository,
                            ReviewCommentRepository reviewCommentRepository,
                            StateMachine stateMachine,
                            GitLabClient gitLabClient,
                            PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.stateMachine = stateMachine;
        this.gitLabClient = gitLabClient;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("GitLabPublisher");
    }

    /**
     * Attempts to publish every unpublished comment of {@code reviewId}. No-op (returns
     * {@link PublishOutcome#NOT_APPLICABLE}) unless the Review is currently {@code COMPLETED} — this
     * is the OBSOLETE/CANCELLED guard from req. 1.10 (those, and every other non-COMPLETED status,
     * are simply not eligible).
     */
    @Transactional(readOnly = true)
    public PublishOutcome publishReview(Long reviewId) {
        Review review = reviewRepository.findById(reviewId).orElseThrow(() -> new ReviewNotFoundException(reviewId));
        if (review.getStatus() != ReviewStatus.COMPLETED) {
            log.debug("Skipping publish for reviewId={}: status={} (not COMPLETED)", reviewId, review.getStatus());
            return PublishOutcome.NOT_APPLICABLE;
        }

        List<ReviewComment> unpublished = reviewCommentRepository.findByReviewIdAndPublishedAtIsNull(reviewId);
        boolean anyFailure = false;
        for (ReviewComment comment : unpublished) {
            try {
                publishOneComment(review.getProjectId(), review.getMergeRequestId(), comment.getId());
            } catch (GitLabPublishException transientFailure) {
                anyFailure = true;
                log.warn("Transient GitLab publish failure for reviewId={} commentId={}: {}",
                        reviewId, comment.getId(), transientFailure.getMessage());
            }
        }

        if (anyFailure) {
            return PublishOutcome.PARTIAL;
        }

        boolean finalized = requiresNewTransactionTemplate.execute(status -> finalizePublished(reviewId));
        return finalized ? PublishOutcome.PUBLISHED : PublishOutcome.PARTIAL;
    }

    private void publishOneComment(Long projectId, Long mergeRequestId, Long commentId) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            ReviewComment fresh = reviewCommentRepository.findById(commentId).orElse(null);
            if (fresh == null || fresh.getPublishedAt() != null) {
                return; // already published concurrently (e.g. a racing retry sweep) - idempotent skip
            }
            String discussionId = gitLabClient.postDiscussion(projectId, mergeRequestId, fresh.getComment());
            fresh.setDiscussionId(discussionId);
            fresh.setPublishedAt(Instant.now());
            reviewCommentRepository.save(fresh);
        });
    }

    /**
     * Re-checks the Review is still COMPLETED (it may have raced to OBSOLETE/CANCELLED while
     * comments were being posted) before flipping it to PUBLISHED, and re-checks that no comment is
     * still unpublished (a concurrent publish attempt, or a comment created after the initial fetch).
     *
     * @return whether the Review actually transitioned to PUBLISHED
     */
    private boolean finalizePublished(Long reviewId) {
        Review fresh = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalStateException("Review " + reviewId + " vanished during publish finalization"));
        if (fresh.getStatus() != ReviewStatus.COMPLETED) {
            log.debug("Not finalizing publish for reviewId={}: status changed to {} mid-publish", reviewId, fresh.getStatus());
            return false;
        }
        long stillUnpublished = reviewCommentRepository.findByReviewIdAndPublishedAtIsNull(reviewId).size();
        if (stillUnpublished > 0) {
            log.debug("Not finalizing publish for reviewId={}: {} comment(s) still unpublished", reviewId, stillUnpublished);
            return false;
        }
        stateMachine.transition(fresh, ReviewStatus.PUBLISHED, EventType.PUBLISHED, "all comments published");
        reviewRepository.save(fresh);
        return true;
    }
}

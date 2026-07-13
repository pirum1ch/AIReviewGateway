package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place retry-vs-fail decisions are made (req. 1.8): {@code attempts} is already
 * incremented at claim time (architecture §4 row 5 guard note), so a value strictly below
 * {@code max-attempts} still has another try coming (-&gt; requeue), while a value at or above the
 * limit is exhausted (-&gt; FAILED). Called by {@link TimeoutManager} for heartbeat-timeout and
 * max-duration cases; Worker/Backend never call this directly (they have no retry logic of their own,
 * req. 1.8).
 */
@Service
public class RetryManager {

    private static final Logger log = LoggerFactory.getLogger(RetryManager.class);

    private final ReviewRepository reviewRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final StateMachine stateMachine;
    private final GatewayProperties properties;

    public RetryManager(ReviewRepository reviewRepository,
                         ReviewJobRepository reviewJobRepository,
                         StateMachine stateMachine,
                         GatewayProperties properties) {
        this.reviewRepository = reviewRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.stateMachine = stateMachine;
        this.properties = properties;
    }

    /**
     * Requeues or fails the given Review. Idempotent: if it already left {@code RUNNING} (e.g. a
     * concurrent result arrived, or a previous sweep already handled it), this is a silent no-op —
     * safe to call repeatedly or after a Gateway restart.
     */
    @Transactional
    public void requeueOrFail(Long reviewId, String reason) {
        Review review = reviewRepository.findById(reviewId).orElse(null);
        if (review == null) {
            log.warn("requeueOrFail called for missing reviewId={}", reviewId);
            return;
        }
        if (review.getStatus() != ReviewStatus.RUNNING) {
            log.debug("Review {} no longer RUNNING ({}), skipping retry/fail sweep", reviewId, review.getStatus());
            return;
        }

        ReviewJob job = reviewJobRepository.findByReviewId(reviewId).orElse(null);
        String workerId = job != null ? job.getWorkerId() : null;
        Long backendId = job != null ? job.getBackendId() : null;
        int maxAttempts = properties.getRetry().getMaxAttempts();

        if (review.getAttempts() >= maxAttempts) {
            stateMachine.transition(review, ReviewStatus.FAILED, EventType.FAILED, workerId, backendId,
                    reason + " (attempts exhausted: " + review.getAttempts() + "/" + maxAttempts + ")");
            log.info("Review {} FAILED: {} (attempts {}/{})", reviewId, reason, review.getAttempts(), maxAttempts);
        } else {
            stateMachine.transition(review, ReviewStatus.QUEUED, EventType.RETRY, workerId, backendId,
                    reason + " (attempt " + review.getAttempts() + "/" + maxAttempts + ")");
            log.info("Review {} requeued for retry: {} (attempt {}/{})", reviewId, reason, review.getAttempts(), maxAttempts);
        }
        reviewRepository.save(review);
    }
}

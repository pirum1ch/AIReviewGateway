package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 (diff chunking) rewrite: {@link RetryManager} now operates at the job level, locking the job row
 * first (CSR-18) and updating the parent review's derived status independently afterward (via
 * {@link ChunkCoordinator}, CSR-17). Both phases open genuinely separate, committed transactions, so
 * this is a real-database integration test (like {@code ResultProcessorTest}) rather than a pure
 * Mockito unit test.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RetryManagerTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewChunkRepository reviewChunkRepository;
    @Autowired
    private ReviewCommentRepository reviewCommentRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;

    @AfterEach
    void cleanUpCommittedRows() {
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private RetryManager newRetryManager(int maxAttempts) {
        EventService eventService = new EventService(reviewEventRepository);
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        GatewayProperties properties = new GatewayProperties();
        properties.getRetry().setMaxAttempts(maxAttempts);
        ChunkCoordinator chunkCoordinator = new ChunkCoordinator(reviewRepository, reviewJobRepository,
                reviewChunkRepository, reviewCommentRepository, stateMachine, jobStateMachine, properties, entityManager, transactionManager);
        return new RetryManager(reviewJobRepository, jobStateMachine, chunkCoordinator, properties, entityManager, transactionManager);
    }

    private Review persistRunningReview(String headSha) {
        Review review = new Review(1L, 2L, headSha, "base", "v1", 10);
        review.setStatus(ReviewStatus.RUNNING);
        return reviewRepository.saveAndFlush(review);
    }

    private ReviewJob persistRunningJob(Review review, int attempts) {
        Backend backend = backendRepository.saveAndFlush(
                new Backend("backend-retry-" + review.getId(), "https://backend-retry.local", "model", 1));
        ReviewJob job = new ReviewJob(review.getId(), backend.getId(), "worker-7");
        job.setStatus(JobStatus.RUNNING);
        job.setAttempts(attempts);
        return reviewJobRepository.saveAndFlush(job);
    }

    @Test
    void belowMaxAttemptsRequeuesTheJobAndKeepsTheReviewRunning() {
        Review review = persistRunningReview("sha-below-max");
        ReviewJob job = persistRunningJob(review, 2); // 2 < 3 -> still has another try

        RetryManager retryManager = newRetryManager(3);
        retryManager.requeueOrFail(job.getId(), "heartbeat timeout");

        ReviewJob reloadedJob = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloadedJob.getStatus()).isEqualTo(JobStatus.QUEUED);

        Review reloadedReview = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloadedReview.getStatus()).isEqualTo(ReviewStatus.QUEUED);

        List<com.review.gateway.model.ReviewEvent> events =
                reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(review.getId());
        assertThat(events).extracting(com.review.gateway.model.ReviewEvent::getEventType).contains(EventType.RETRY);
    }

    @Test
    void atMaxAttemptsFailsTheJobAndTheReview() {
        Review review = persistRunningReview("sha-at-max");
        ReviewJob job = persistRunningJob(review, 3); // 3 >= 3 -> exhausted

        RetryManager retryManager = newRetryManager(3);
        retryManager.requeueOrFail(job.getId(), "heartbeat timeout");

        ReviewJob reloadedJob = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloadedJob.getStatus()).isEqualTo(JobStatus.FAILED);

        Review reloadedReview = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloadedReview.getStatus()).isEqualTo(ReviewStatus.FAILED);
    }

    @Test
    void jobNoLongerRunningIsANoOp() {
        Review review = persistRunningReview("sha-not-running");
        ReviewJob job = persistRunningJob(review, 1);
        job.setStatus(JobStatus.COMPLETED);
        reviewJobRepository.saveAndFlush(job);

        Review completedReview = reviewRepository.findById(review.getId()).orElseThrow();
        completedReview.setStatus(ReviewStatus.COMPLETED);
        reviewRepository.saveAndFlush(completedReview);

        RetryManager retryManager = newRetryManager(3);
        retryManager.requeueOrFail(job.getId(), "heartbeat timeout");

        ReviewJob reloadedJob = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloadedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
        Review reloadedReview = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloadedReview.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void missingJobIsANoOp() {
        RetryManager retryManager = newRetryManager(3);

        // Must not throw for a job id that doesn't exist.
        retryManager.requeueOrFail(999_999L, "heartbeat timeout");
    }
}

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
import com.review.gateway.service.dto.RequeueOutcome;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
        return newRetryManager(maxAttempts, null);
    }

    private RetryManager newRetryManager(int maxAttempts, java.time.Duration requeueDelay) {
        EventService eventService = new EventService(reviewEventRepository, new TextSanitizer());
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        GatewayProperties properties = new GatewayProperties();
        properties.getRetry().setMaxAttempts(maxAttempts);
        if (requeueDelay != null) {
            properties.getRetry().setRequeueDelay(requeueDelay);
        }
        ChunkCoordinator chunkCoordinator = new ChunkCoordinator(reviewRepository, reviewJobRepository,
                reviewChunkRepository, reviewCommentRepository, stateMachine, jobStateMachine, properties, entityManager, transactionManager);
        return new RetryManager(reviewJobRepository, jobStateMachine, chunkCoordinator, properties,
                new TextSanitizer(), entityManager, transactionManager);
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
        List<com.review.gateway.model.ReviewEvent> retryEvents = events.stream()
                .filter(e -> e.getEventType() == EventType.RETRY)
                .toList();

        // QA regression (metrics double-counting): a single retry legitimately writes TWO RETRY rows --
        // one job-level (JobStateMachine, chunk_index/job_id set) and one review-level (ChunkCoordinator
        // -> StateMachine, chunk_index/job_id null, since the parent's derived status also transitions
        // back to QUEUED). Both rows are correct/expected at the audit-trail level; what must NOT happen
        // is GET /metrics counting both as if they were two separate retries.
        assertThat(retryEvents).hasSize(2);
        assertThat(retryEvents).filteredOn(e -> e.getJobId() != null).hasSize(1);
        assertThat(retryEvents).filteredOn(e -> e.getJobId() == null).hasSize(1);

        // The fix: StatisticsService/GET-metrics must report exactly ONE retry for this one actual
        // retry, not two -- verified directly against the repository method it uses.
        assertThat(reviewEventRepository.countByEventTypeAndJobIdIsNotNull(EventType.RETRY)).isEqualTo(1L);
        assertThat(reviewEventRepository.countByEventType(EventType.RETRY)).isEqualTo(2L);
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

    // WOC-27: the owner-aware overload re-checks ownership INSIDE the job-row lock.
    @Test
    void ownerAwareRequeueOrFailWithMatchingWorkerIdSucceeds() {
        Review review = persistRunningReview("sha-owner-match");
        ReviewJob job = persistRunningJob(review, 1); // owned by "worker-7", see persistRunningJob

        RetryManager retryManager = newRetryManager(3);
        RequeueOutcome outcome = retryManager.requeueOrFail(job.getId(), "worker-reported: reason=LLM_ERROR", "worker-7");

        assertThat(outcome.outcome()).isEqualTo(RequeueOutcome.Outcome.APPLIED_REQUEUED);
        assertThat(outcome.reviewId()).isEqualTo(review.getId());
        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void ownerAwareRequeueOrFailWithWrongWorkerIdIsRejectedWithoutChangingState() {
        Review review = persistRunningReview("sha-owner-mismatch");
        ReviewJob job = persistRunningJob(review, 1); // owned by "worker-7"

        RetryManager retryManager = newRetryManager(3);
        RequeueOutcome outcome = retryManager.requeueOrFail(job.getId(), "worker-reported: reason=LLM_ERROR", "worker-impostor");

        assertThat(outcome.outcome()).isEqualTo(RequeueOutcome.Outcome.OWNERSHIP_MISMATCH);
        assertThat(outcome.reviewId()).isNull();
        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(reloaded.getLastError()).isNull();
    }

    @Test
    void nullExpectedWorkerIdSkipsTheOwnershipCheckLikeTheSweepAlwaysHas() {
        Review review = persistRunningReview("sha-owner-null");
        ReviewJob job = persistRunningJob(review, 1);

        RetryManager retryManager = newRetryManager(3);
        RequeueOutcome outcome = retryManager.requeueOrFail(job.getId(), "heartbeat timeout", null);

        assertThat(outcome.outcome()).isEqualTo(RequeueOutcome.Outcome.APPLIED_REQUEUED);
    }

    // WOC-29: review_jobs.last_error is written on both the requeue and fail branches.
    @Test
    void lastErrorIsWrittenOnTheRequeueBranch() {
        Review review = persistRunningReview("sha-last-error-requeue");
        ReviewJob job = persistRunningJob(review, 1);

        RetryManager retryManager = newRetryManager(3);
        retryManager.requeueOrFail(job.getId(), "heartbeat timeout");

        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getLastError()).contains("heartbeat timeout").contains("attempt");
    }

    @Test
    void lastErrorIsWrittenOnTheFailBranch() {
        Review review = persistRunningReview("sha-last-error-fail");
        ReviewJob job = persistRunningJob(review, 3); // exhausted

        RetryManager retryManager = newRetryManager(3);
        retryManager.requeueOrFail(job.getId(), "max duration exceeded");

        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getLastError()).contains("max duration exceeded").contains("attempts exhausted");
    }

    // WOC-40/WOR-14: not_before is set on the requeue branch, computed against the DATABASE clock, and
    // omitted entirely (disabled) when requeue-delay is 0.
    @Test
    void notBeforeIsSetOnRequeueWhenRequeueDelayIsConfigured() {
        Review review = persistRunningReview("sha-not-before");
        ReviewJob job = persistRunningJob(review, 1);

        RetryManager retryManager = newRetryManager(3, Duration.ofSeconds(90));
        retryManager.requeueOrFail(job.getId(), "heartbeat timeout");

        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getNotBefore()).isNotNull();
        assertThat(reloaded.getNotBefore()).isAfter(java.time.Instant.now().plusSeconds(60));
    }

    @Test
    void notBeforeStaysNullWhenRequeueDelayIsZero() {
        Review review = persistRunningReview("sha-not-before-disabled");
        ReviewJob job = persistRunningJob(review, 1);

        RetryManager retryManager = newRetryManager(3, Duration.ZERO);
        retryManager.requeueOrFail(job.getId(), "heartbeat timeout");

        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getNotBefore()).isNull();
    }

    /**
     * Sibling cancellation on chunk-job permanent failure (§4): once one chunk job exhausts its
     * retries and lands FAILED, the parent Review transitions to FAILED and every other non-terminal
     * sibling job (QUEUED or RUNNING) is cancelled in the same transaction (parent-then-child, CSR-17).
     */
    @Test
    void permanentJobFailureCascadesToFailTheReviewAndCancelNonTerminalSiblings() {
        Review review = persistRunningReview("sha-sibling-cancel");
        Backend backend = backendRepository.saveAndFlush(
                new Backend("backend-sibling-" + review.getId(), "https://backend-sibling.local", "model", 1));

        ReviewJob failingJob = new ReviewJob(review.getId(), null, 0, 10, "worker-a", backend.getId());
        failingJob.setStatus(JobStatus.RUNNING);
        failingJob.setAttempts(3); // exhausted
        failingJob = reviewJobRepository.saveAndFlush(failingJob);

        ReviewJob runningSibling = new ReviewJob(review.getId(), null, 1, 10, "worker-b", backend.getId());
        runningSibling.setStatus(JobStatus.RUNNING);
        runningSibling = reviewJobRepository.saveAndFlush(runningSibling);

        ReviewJob queuedSibling = new ReviewJob(review.getId(), null, 2, 10, null, null);
        queuedSibling = reviewJobRepository.saveAndFlush(queuedSibling);

        RetryManager retryManager = newRetryManager(3);
        retryManager.requeueOrFail(failingJob.getId(), "llama error");

        ReviewJob reloadedFailing = reviewJobRepository.findById(failingJob.getId()).orElseThrow();
        assertThat(reloadedFailing.getStatus()).isEqualTo(JobStatus.FAILED);

        ReviewJob reloadedRunningSibling = reviewJobRepository.findById(runningSibling.getId()).orElseThrow();
        assertThat(reloadedRunningSibling.getStatus()).isEqualTo(JobStatus.CANCELLED);

        ReviewJob reloadedQueuedSibling = reviewJobRepository.findById(queuedSibling.getId()).orElseThrow();
        assertThat(reloadedQueuedSibling.getStatus()).isEqualTo(JobStatus.CANCELLED);

        Review reloadedReview = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloadedReview.getStatus()).isEqualTo(ReviewStatus.FAILED);

        List<com.review.gateway.model.ReviewEvent> events =
                reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(review.getId());
        long cancelledEventCount = events.stream().filter(e -> e.getEventType() == EventType.CANCELLED).count();
        assertThat(cancelledEventCount).isEqualTo(2);
    }
}

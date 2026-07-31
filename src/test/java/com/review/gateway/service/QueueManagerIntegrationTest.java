package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewChunk;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewPromptSectionRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.ClaimedJob;
import com.review.gateway.service.dto.HeartbeatOutcome;
import com.review.gateway.service.dto.HeartbeatResult;
import com.review.gateway.service.dto.ResultOutcome;
import com.review.gateway.service.dto.SubmitResultCommand;
import com.review.gateway.service.dto.SubmitResultOutcome;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link QueueManager} end-to-end against a real (Zonky) PostgreSQL instance: claim's
 * capacity/SKIP-LOCKED path, heartbeat ownership (SR-04), and result submission's idempotent-no-op
 * short-circuit. V2 (diff chunking): the queue lives on {@code review_jobs}, so every fixture creates
 * a single-chunk {@link ReviewChunk} + {@link ReviewJob} alongside the {@link Review}.
 *
 * <p>{@code @Transactional(NOT_SUPPORTED)}: {@link QueueManager#claim} (CSR-17) genuinely opens its own
 * separate, independently-committed transactions (via {@code TransactionTemplate}, not a proxied
 * {@code @Transactional} — this works even though {@code QueueManager} is constructed directly here,
 * bypassing Spring AOP, precisely because {@code TransactionTemplate} manages the transaction itself).
 * Fixture rows must therefore be genuinely committed (not merely flushed within an ambient, never-
 * committed per-test transaction) for {@code claim}'s separate transaction to see them under
 * read-committed isolation — exactly the same reasoning as {@code ResultProcessorTest}.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QueueManagerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewInputRepository reviewInputRepository;
    @Autowired
    private ReviewChunkRepository reviewChunkRepository;
    @Autowired
    private ReviewCommentRepository reviewCommentRepository;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private ReviewPromptSectionRepository reviewPromptSectionRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUpCommittedRows() {
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private QueueManager newQueueManager(ResultProcessor resultProcessor) {
        EventService eventService = new EventService(reviewEventRepository);
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        BackendDispatcher backendDispatcher = new BackendDispatcher(backendRepository, reviewJobRepository);
        GatewayProperties properties = new GatewayProperties();
        ChunkCoordinator chunkCoordinator = new ChunkCoordinator(reviewRepository, reviewJobRepository,
                reviewChunkRepository, reviewCommentRepository, stateMachine, jobStateMachine, properties, entityManager, transactionManager);
        ChunkContextRenderer chunkContextRenderer = new ChunkContextRenderer(properties, new TextSanitizer());
        PromptMessageFormatter promptMessageFormatter = new PromptMessageFormatter(properties);
        return new QueueManager(reviewRepository, reviewJobRepository, reviewChunkRepository,
                reviewPromptSectionRepository, backendDispatcher, jobStateMachine, chunkCoordinator, eventService,
                resultProcessor, chunkContextRenderer, promptMessageFormatter, entityManager, transactionManager);
    }

    private Review persistQueuedReview(long projectId, long mrId, String headSha, int priority) {
        Review review = new Review(projectId, mrId, headSha, "base", "v1", priority);
        review.setStatus(ReviewStatus.QUEUED);
        review = reviewRepository.saveAndFlush(review);
        reviewInputRepository.saveAndFlush(new ReviewInput(review.getId(), "diff-" + headSha, "v1", headSha, "base", 10));
        ReviewChunk chunk = reviewChunkRepository.saveAndFlush(
                new ReviewChunk(review.getId(), 0, 1, "diff-" + headSha, 10, 0, "[]"));
        reviewJobRepository.saveAndFlush(new ReviewJob(review.getId(), chunk.getId(), 0, priority, null, null));
        return review;
    }

    private Backend persistBackend(String name, BackendStatus status, int capacity) {
        Backend backend = new Backend(name, "https://" + name + ".local", "model-x", capacity);
        backend.setStatus(status);
        return backendRepository.saveAndFlush(backend);
    }

    @Test
    void claimHappyPathClaimsHighestPriorityQueuedReview() {
        persistBackend("mac-mini-a", BackendStatus.ACTIVE, 2);
        Review review = persistQueuedReview(1L, 100L, "sha-a", 10);

        QueueManager queueManager = newQueueManager(Mockito.mock(ResultProcessor.class));
        Optional<ClaimedJob> claimed = queueManager.claim("mac-mini-a", "worker-1");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().reviewId()).isEqualTo(review.getId());
        assertThat(claimed.get().diff()).isEqualTo("diff-sha-a");
        assertThat(claimed.get().chunkContext()).isNull(); // single chunk -> no context header (§8)

        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.RUNNING);

        ReviewJob job = reviewJobRepository.findByReviewIdAndChunkIndex(review.getId(), 0).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getWorkerId()).isEqualTo("worker-1");
    }

    @Test
    void claimReturnsEmptyWhenQueueIsEmpty() {
        persistBackend("mac-mini-b", BackendStatus.ACTIVE, 2);

        QueueManager queueManager = newQueueManager(Mockito.mock(ResultProcessor.class));
        Optional<ClaimedJob> claimed = queueManager.claim("mac-mini-b", "worker-1");

        assertThat(claimed).isEmpty();
    }

    @Test
    void claimReturnsEmptyWhenBackendIsNotActive() {
        persistBackend("mac-mini-c", BackendStatus.SUSPECT, 2);
        persistQueuedReview(1L, 101L, "sha-c", 10);

        QueueManager queueManager = newQueueManager(Mockito.mock(ResultProcessor.class));
        Optional<ClaimedJob> claimed = queueManager.claim("mac-mini-c", "worker-1");

        assertThat(claimed).isEmpty();
    }

    @Test
    void claimReturnsEmptyWhenBackendIsAtCapacity() {
        Backend backend = persistBackend("mac-mini-d", BackendStatus.ACTIVE, 1);
        Review runningElsewhere = persistQueuedReview(1L, 102L, "sha-d-running", 10);
        runningElsewhere.setStatus(ReviewStatus.RUNNING);
        reviewRepository.saveAndFlush(runningElsewhere);
        ReviewJob runningJob = reviewJobRepository.findByReviewIdAndChunkIndex(runningElsewhere.getId(), 0).orElseThrow();
        runningJob.setStatus(JobStatus.RUNNING);
        runningJob.setBackendId(backend.getId());
        runningJob.setWorkerId("worker-existing");
        reviewJobRepository.saveAndFlush(runningJob);

        persistQueuedReview(1L, 103L, "sha-d-queued", 10);

        QueueManager queueManager = newQueueManager(Mockito.mock(ResultProcessor.class));
        Optional<ClaimedJob> claimed = queueManager.claim("mac-mini-d", "worker-1");

        assertThat(claimed).isEmpty();
    }

    @Test
    void heartbeatFromTheOwningWorkerUpdatesHeartbeatAndContinues() {
        persistBackend("mac-mini-e", BackendStatus.ACTIVE, 2);
        persistQueuedReview(1L, 104L, "sha-e", 10);

        QueueManager queueManager = newQueueManager(Mockito.mock(ResultProcessor.class));
        ClaimedJob claimed = queueManager.claim("mac-mini-e", "worker-1").orElseThrow();

        HeartbeatResult result = queueManager.heartbeat(claimed.jobId(), "worker-1");

        assertThat(result.outcome()).isEqualTo(HeartbeatOutcome.ACCEPTED);
        assertThat(result.shouldContinue()).isTrue();
    }

    @Test
    void heartbeatFromAWrongWorkerIsRejectedWithoutMutatingState() {
        persistBackend("mac-mini-f", BackendStatus.ACTIVE, 2);
        persistQueuedReview(1L, 105L, "sha-f", 10);

        QueueManager queueManager = newQueueManager(Mockito.mock(ResultProcessor.class));
        ClaimedJob claimed = queueManager.claim("mac-mini-f", "worker-1").orElseThrow();

        ReviewJob beforeJob = reviewJobRepository.findById(claimed.jobId()).orElseThrow();
        Instant beforeHeartbeat = beforeJob.getHeartbeatAt();

        HeartbeatResult result = queueManager.heartbeat(claimed.jobId(), "worker-IMPOSTOR");

        assertThat(result.outcome()).isEqualTo(HeartbeatOutcome.OWNERSHIP_MISMATCH);
        assertThat(result.shouldContinue()).isFalse();

        ReviewJob afterJob = reviewJobRepository.findById(claimed.jobId()).orElseThrow();
        assertThat(afterJob.getHeartbeatAt()).isEqualTo(beforeHeartbeat);
    }

    @Test
    void heartbeatForUnknownJobIsNotFound() {
        QueueManager queueManager = newQueueManager(Mockito.mock(ResultProcessor.class));

        HeartbeatResult result = queueManager.heartbeat(999_999L, "worker-1");

        assertThat(result.outcome()).isEqualTo(HeartbeatOutcome.NOT_FOUND);
        assertThat(result.shouldContinue()).isFalse();
    }

    @Test
    void heartbeatWhenReviewIsNoLongerRunningReportsShouldNotContinue() {
        persistBackend("mac-mini-g", BackendStatus.ACTIVE, 2);
        Review review = persistQueuedReview(1L, 106L, "sha-g", 10);

        QueueManager queueManager = newQueueManager(Mockito.mock(ResultProcessor.class));
        ClaimedJob claimed = queueManager.claim("mac-mini-g", "worker-1").orElseThrow();

        // Simulate the review going OBSOLETE concurrently (e.g. a new head_sha arrived) -- the job
        // itself is still RUNNING (a real OBSOLETE sweep would also cascade to the job, but this test
        // deliberately isolates the heartbeat's "review no longer RUNNING" check).
        Review running = reviewRepository.findById(review.getId()).orElseThrow();
        running.setStatus(ReviewStatus.OBSOLETE);
        reviewRepository.saveAndFlush(running);

        HeartbeatResult result = queueManager.heartbeat(claimed.jobId(), "worker-1");

        assertThat(result.outcome()).isEqualTo(HeartbeatOutcome.ACCEPTED);
        assertThat(result.shouldContinue()).isFalse();
    }

    @Test
    void submitResultIsIdempotentNoOpWhenJobIsNoLongerRunning() {
        persistBackend("mac-mini-h", BackendStatus.ACTIVE, 2);
        Review review = persistQueuedReview(1L, 107L, "sha-h", 10);

        ResultProcessor resultProcessor = Mockito.mock(ResultProcessor.class);
        QueueManager queueManager = newQueueManager(resultProcessor);
        ClaimedJob claimed = queueManager.claim("mac-mini-h", "worker-1").orElseThrow();

        // Simulate the job already having completed via a prior (or concurrent) result delivery.
        ReviewJob job = reviewJobRepository.findById(claimed.jobId()).orElseThrow();
        job.setStatus(JobStatus.COMPLETED);
        reviewJobRepository.saveAndFlush(job);
        Review completed = reviewRepository.findById(review.getId()).orElseThrow();
        completed.setStatus(ReviewStatus.COMPLETED);
        reviewRepository.saveAndFlush(completed);

        SubmitResultOutcome outcome = queueManager.submitResult(claimed.jobId(), "worker-1",
                new SubmitResultCommand("raw response", 10, 20, 500L, "model-x"));

        assertThat(outcome.outcome()).isEqualTo(ResultOutcome.IDEMPOTENT_NOOP);
        assertThat(outcome.currentStatus()).isEqualTo(ReviewStatus.COMPLETED);
        verify(resultProcessor, never()).process(any(), any(), any(), any(), any());
    }

    @Test
    void submitResultOwnershipMismatchDoesNotDelegateToResultProcessor() {
        persistBackend("mac-mini-i", BackendStatus.ACTIVE, 2);
        persistQueuedReview(1L, 108L, "sha-i", 10);

        ResultProcessor resultProcessor = Mockito.mock(ResultProcessor.class);
        QueueManager queueManager = newQueueManager(resultProcessor);
        ClaimedJob claimed = queueManager.claim("mac-mini-i", "worker-1").orElseThrow();

        SubmitResultOutcome outcome = queueManager.submitResult(claimed.jobId(), "worker-IMPOSTOR",
                new SubmitResultCommand("raw response", 10, 20, 500L, "model-x"));

        assertThat(outcome.outcome()).isEqualTo(ResultOutcome.OWNERSHIP_MISMATCH);
        verify(resultProcessor, never()).process(any(), any(), any(), any(), any());
    }

    @Test
    void submitResultForUnknownJobIsNotFound() {
        QueueManager queueManager = newQueueManager(Mockito.mock(ResultProcessor.class));

        SubmitResultOutcome outcome = queueManager.submitResult(999_999L, "worker-1",
                new SubmitResultCommand("raw", 1, 1, 1L, "model"));

        assertThat(outcome.outcome()).isEqualTo(ResultOutcome.NOT_FOUND);
    }

    @Test
    void submitResultDelegatesToResultProcessorWhenRunning() {
        persistBackend("mac-mini-j", BackendStatus.ACTIVE, 2);
        persistQueuedReview(1L, 109L, "sha-j", 10);

        ResultProcessor resultProcessor = Mockito.mock(ResultProcessor.class);
        QueueManager queueManager = newQueueManager(resultProcessor);
        ClaimedJob claimed = queueManager.claim("mac-mini-j", "worker-1").orElseThrow();

        when(resultProcessor.process(any(), any(), any(), any(), any())).thenReturn(ReviewStatus.COMPLETED);

        SubmitResultOutcome outcome = queueManager.submitResult(claimed.jobId(), "worker-1",
                new SubmitResultCommand("raw response", 10, 20, 500L, "model-x"));

        assertThat(outcome.outcome()).isEqualTo(ResultOutcome.ACCEPTED);
        assertThat(outcome.currentStatus()).isEqualTo(ReviewStatus.COMPLETED);
    }
}

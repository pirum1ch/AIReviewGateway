package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.ClaimedJob;
import com.review.gateway.service.dto.HeartbeatOutcome;
import com.review.gateway.service.dto.HeartbeatResult;
import com.review.gateway.service.dto.ResultOutcome;
import com.review.gateway.service.dto.SubmitResultCommand;
import com.review.gateway.service.dto.SubmitResultOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

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
 * short-circuit. {@link ResultProcessor} is mocked here since its own behavior (raw-stored-before-
 * parse, COMPLETED/FAILED transitions) is covered in depth by {@link ResultProcessorTest}.
 *
 * <p>Services are constructed directly ({@code new QueueManager(...)}) rather than obtained from a
 * Spring context, wired against real, {@code @Autowired} repositories. This is a deliberate
 * simplification: true cross-statement atomicity of the claim transaction under concurrency is
 * already covered at the repository layer by {@code ReviewRepositoryConcurrentClaimTest}
 * (feature/01-data-model); this test verifies claim's *functional* behavior (capacity, status,
 * payload) end-to-end against a real DB in a single thread.
 */
class QueueManagerIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewInputRepository reviewInputRepository;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private TestEntityManager entityManager;

    private QueueManager newQueueManager(ResultProcessor resultProcessor) {
        EventService eventService = new EventService(reviewEventRepository);
        StateMachine stateMachine = new StateMachine(eventService);
        BackendDispatcher backendDispatcher = new BackendDispatcher(backendRepository, reviewJobRepository);
        return new QueueManager(reviewRepository, reviewJobRepository, reviewInputRepository,
                backendDispatcher, stateMachine, eventService, resultProcessor);
    }

    private Review persistQueuedReview(long projectId, long mrId, String headSha, int priority) {
        Review review = new Review(projectId, mrId, headSha, "base", "v1", priority);
        review.setStatus(ReviewStatus.QUEUED);
        Review saved = entityManager.persistFlushFind(review);
        entityManager.persistAndFlush(new ReviewInput(saved.getId(), "diff-" + headSha, "v1", headSha, "base", 10));
        return saved;
    }

    private Backend persistBackend(String name, BackendStatus status, int capacity) {
        Backend backend = new Backend(name, "https://" + name + ".local", "model-x", capacity);
        backend.setStatus(status);
        return entityManager.persistFlushFind(backend);
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

        entityManager.getEntityManager().flush();
        entityManager.getEntityManager().clear();
        Review reloaded = entityManager.find(Review.class, review.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.RUNNING);
        assertThat(reloaded.getAttempts()).isEqualTo(1);

        Optional<ReviewJob> job = reviewJobRepository.findByReviewId(review.getId());
        assertThat(job).isPresent();
        assertThat(job.get().getWorkerId()).isEqualTo("worker-1");
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
        entityManager.persistAndFlush(runningElsewhere);
        entityManager.persistAndFlush(new ReviewJob(runningElsewhere.getId(), backend.getId(), "worker-existing"));

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

        // Flush + clear so this read (and the one below) both hit the DB fresh, at its actual
        // stored (microsecond) precision -- otherwise the first read would return the still-attached,
        // full-nanosecond-precision Java instant from the same persistence context, which would never
        // compare equal to a genuinely re-fetched value.
        entityManager.getEntityManager().flush();
        entityManager.getEntityManager().clear();
        ReviewJob beforeJob = reviewJobRepository.findById(claimed.jobId()).orElseThrow();
        Instant beforeHeartbeat = beforeJob.getHeartbeatAt();

        HeartbeatResult result = queueManager.heartbeat(claimed.jobId(), "worker-IMPOSTOR");

        assertThat(result.outcome()).isEqualTo(HeartbeatOutcome.OWNERSHIP_MISMATCH);
        assertThat(result.shouldContinue()).isFalse();

        entityManager.getEntityManager().flush();
        entityManager.getEntityManager().clear();
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

        // Simulate the review going OBSOLETE concurrently (e.g. a new head_sha arrived).
        Review running = entityManager.find(Review.class, review.getId());
        running.setStatus(ReviewStatus.OBSOLETE);
        entityManager.persistAndFlush(running);

        HeartbeatResult result = queueManager.heartbeat(claimed.jobId(), "worker-1");

        assertThat(result.outcome()).isEqualTo(HeartbeatOutcome.ACCEPTED);
        assertThat(result.shouldContinue()).isFalse();
    }

    @Test
    void submitResultIsIdempotentNoOpWhenReviewIsNoLongerRunning() {
        persistBackend("mac-mini-h", BackendStatus.ACTIVE, 2);
        Review review = persistQueuedReview(1L, 107L, "sha-h", 10);

        ResultProcessor resultProcessor = Mockito.mock(ResultProcessor.class);
        QueueManager queueManager = newQueueManager(resultProcessor);
        ClaimedJob claimed = queueManager.claim("mac-mini-h", "worker-1").orElseThrow();

        // Simulate the review already having completed via a prior (or concurrent) result delivery.
        Review completed = entityManager.find(Review.class, review.getId());
        completed.setStatus(ReviewStatus.COMPLETED);
        entityManager.persistAndFlush(completed);

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

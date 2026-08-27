package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewEvent;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.service.dto.FailureReportOutcome;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewPromptSectionRepository;
import com.review.gateway.repository.ReviewRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /jobs/{id}/fail} end-to-end against a real (Zonky) PostgreSQL instance (architecture §5,
 * WOC-26..WOC-33, WOR-01..WOR-19; test guidance T-3.1/2/3/4/10/11). Same fixture conventions as
 * {@code QueueManagerIntegrationTest}/{@code RetryManagerTest}.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QueueManagerReportFailureTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
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

    private QueueManager newQueueManager(int maxAttempts) {
        EventService eventService = new EventService(reviewEventRepository, new TextSanitizer());
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        GatewayProperties properties = new GatewayProperties();
        properties.getRetry().setMaxAttempts(maxAttempts);
        properties.getRetry().setRequeueDelay(Duration.ofSeconds(90));
        BackendDispatcher backendDispatcher = new BackendDispatcher(backendRepository, reviewJobRepository, properties);
        ChunkCoordinator chunkCoordinator = new ChunkCoordinator(reviewRepository, reviewJobRepository,
                reviewChunkRepository, reviewCommentRepository, stateMachine, jobStateMachine, properties,
                entityManager, transactionManager);
        ChunkContextRenderer chunkContextRenderer = new ChunkContextRenderer(properties, new TextSanitizer());
        PromptMessageFormatter promptMessageFormatter = new PromptMessageFormatter(properties,
                new PromptAssembler(properties, new DiffSizeValidator(properties)));
        RetryManager retryManager = new RetryManager(reviewJobRepository, jobStateMachine, chunkCoordinator,
                properties, new TextSanitizer(), entityManager, transactionManager);
        return new QueueManager(reviewRepository, reviewJobRepository, reviewChunkRepository,
                reviewPromptSectionRepository, backendDispatcher, jobStateMachine, chunkCoordinator, eventService,
                Mockito.mock(ResultProcessor.class), chunkContextRenderer, promptMessageFormatter, retryManager,
                new TextSanitizer(), new MetricsCounters(), new ReviewSchemaBuilder(), new DecoderConstraintRenderer(),
                properties, entityManager, transactionManager);
    }

    private Review persistRunningReview(String headSha) {
        Review review = new Review(1L, 2L, headSha, "base", "v1", 10);
        review.setStatus(ReviewStatus.RUNNING);
        return reviewRepository.saveAndFlush(review);
    }

    private ReviewJob persistRunningJob(Review review, int attempts, String workerId) {
        Backend backend = backendRepository.saveAndFlush(
                new Backend("backend-fail-" + review.getId(), "https://backend-fail.local", "model", 1));
        ReviewJob job = new ReviewJob(review.getId(), backend.getId(), workerId);
        job.setStatus(JobStatus.RUNNING);
        job.setAttempts(attempts);
        return reviewJobRepository.saveAndFlush(job);
    }

    // T-3.1
    @Test
    void reportOnRunningOwnedJobRequeuesTheJobAndSetsLastErrorAndNotBefore() {
        Review review = persistRunningReview("sha-report-1");
        ReviewJob job = persistRunningJob(review, 1, "worker-owner");

        FailureReportOutcome outcome = report(newQueueManager(3), job.getId(), "worker-owner", "LLM_TIMEOUT", "detail text");

        assertThat(outcome).isEqualTo(FailureReportOutcome.ACCEPTED);
        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(reloaded.getLastError()).contains("worker-reported: reason=LLM_TIMEOUT").contains("detail text");
        assertThat(reloaded.getNotBefore()).isNotNull();

        List<ReviewEvent> events = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(review.getId());
        assertThat(events).anyMatch(e -> e.getEventType() == EventType.RETRY
                && e.getJobId() != null
                && e.getDetails() != null
                && e.getDetails().startsWith("worker-reported: reason=LLM_TIMEOUT"));
    }

    // T-3.2
    @Test
    void reportAtMaxAttemptsFailsTheJobAndCascadesToSiblings() {
        Review review = persistRunningReview("sha-report-2");
        ReviewJob failingJob = persistRunningJob(review, 3, "worker-owner");
        ReviewJob sibling = new ReviewJob(review.getId(), null, 1, 10, "worker-b", failingJob.getBackendId());
        sibling.setStatus(JobStatus.RUNNING);
        sibling = reviewJobRepository.saveAndFlush(sibling);

        report(newQueueManager(3), failingJob.getId(), "worker-owner", "LLM_ERROR", null);

        ReviewJob reloadedFailing = reviewJobRepository.findById(failingJob.getId()).orElseThrow();
        assertThat(reloadedFailing.getStatus()).isEqualTo(JobStatus.FAILED);
        ReviewJob reloadedSibling = reviewJobRepository.findById(sibling.getId()).orElseThrow();
        assertThat(reloadedSibling.getStatus()).isEqualTo(JobStatus.CANCELLED);
        Review reloadedReview = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloadedReview.getStatus()).isEqualTo(ReviewStatus.FAILED);
    }

    // T-3.3
    @Test
    void duplicateReportIsIdempotentAndWritesNoSecondRetryEvent() {
        Review review = persistRunningReview("sha-report-3");
        ReviewJob job = persistRunningJob(review, 1, "worker-owner");
        QueueManager queueManager = newQueueManager(3);

        report(queueManager, job.getId(), "worker-owner", "LLM_ERROR", null);
        long retryEventsAfterFirst = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(review.getId())
                .stream().filter(e -> e.getEventType() == EventType.RETRY && e.getJobId() != null).count();
        assertThat(retryEventsAfterFirst).isEqualTo(1);

        // The job is now QUEUED, not RUNNING -- a second report is an idempotent no-op.
        FailureReportOutcome secondOutcome = report(queueManager, job.getId(), "worker-owner", "LLM_ERROR", null);
        assertThat(secondOutcome).isEqualTo(FailureReportOutcome.ACCEPTED);

        long retryEventsAfterSecond = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(review.getId())
                .stream().filter(e -> e.getEventType() == EventType.RETRY && e.getJobId() != null).count();
        assertThat(retryEventsAfterSecond).isEqualTo(1);
    }

    // T-3.4
    @Test
    void reportByNonOwnerIsRejectedWithoutChangingState() {
        Review review = persistRunningReview("sha-report-4");
        ReviewJob job = persistRunningJob(review, 1, "worker-real-owner");

        FailureReportOutcome outcome = report(newQueueManager(3), job.getId(), "worker-impostor", "LLM_ERROR", null);

        assertThat(outcome).isEqualTo(FailureReportOutcome.OWNERSHIP_MISMATCH);
        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(reloaded.getLastError()).isNull();
    }

    @Test
    void reportForUnknownJobIsNotFound() {
        FailureReportOutcome outcome = report(newQueueManager(3), 999_999L, "worker-1", "LLM_ERROR", null);

        assertThat(outcome).isEqualTo(FailureReportOutcome.NOT_FOUND);
    }

    /**
     * SGB-06/SOGB-07 (Structured Output Grammar Budget): {@code CONSTRAINT_REJECTED} requeues via the
     * exact SAME attempts-based path as any other reason -- no special-casing, no branch, same as
     * {@code reportOnRunningOwnedJobRequeuesTheJobAndSetsLastErrorAndNotBefore} above. This is the
     * functional half of the SOGB-07 inertness guarantee ({@code RetryManagerNoJobFailureReasonDependencyTest}
     * is the structural half).
     */
    @Test
    void constraintRejectedReasonRequeuesIdenticallyToAnyOtherWorkerReportedReason() {
        Review review = persistRunningReview("sha-report-constraint-rejected");
        ReviewJob job = persistRunningJob(review, 1, "worker-owner");

        FailureReportOutcome outcome = report(newQueueManager(3), job.getId(), "worker-owner",
                "CONSTRAINT_REJECTED", "llama-server refused the decoder-constraint grammar (compile-time rejection)");

        assertThat(outcome).isEqualTo(FailureReportOutcome.ACCEPTED);
        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(reloaded.getLastError()).contains("worker-reported: reason=CONSTRAINT_REJECTED");
        assertThat(reloaded.getNotBefore()).isNotNull();
    }

    /**
     * SOGB-07: no backend-supplied raw error text ever reaches {@code review_jobs.last_error} un-
     * sanitized via this new reason -- the Worker's own {@code detail} for {@code CONSTRAINT_REJECTED} is
     * always one of {@code WorkerLoop.DETAIL_BY_REASON}'s fixed constants, never backend-echoed text, but
     * this proves the Gateway's existing generic sanitize/cap still applies to this value too, exactly as
     * it does for every other reason ({@code detailWithCrlfAndControlCharsIsSanitizedAndTruncated} below).
     */
    @Test
    void constraintRejectedDetailIsSanitizedAndCappedLikeAnyOtherReason() {
        Review review = persistRunningReview("sha-report-constraint-rejected-sanitize");
        ReviewJob job = persistRunningJob(review, 1, "worker-owner");
        String maliciousDetail = "failed to parse grammar\r\nFORGED line" + "x".repeat(400);

        report(newQueueManager(3), job.getId(), "worker-owner", "CONSTRAINT_REJECTED", maliciousDetail);

        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getLastError()).doesNotContain("\r").doesNotContain("\n");
        assertThat(reloaded.getLastError().length()).isLessThanOrEqualTo(512);
    }

    // T-3.10
    @Test
    void unknownReasonCodeIsAcceptedAndClassifiedAsUnknown() {
        Review review = persistRunningReview("sha-report-unknown-reason");
        ReviewJob job = persistRunningJob(review, 1, "worker-owner");

        FailureReportOutcome outcome = report(newQueueManager(3), job.getId(), "worker-owner", "SOMETHING_MADE_UP", null);

        assertThat(outcome).isEqualTo(FailureReportOutcome.ACCEPTED);
        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getLastError()).contains("reason=UNKNOWN");
    }

    // T-3.11
    @Test
    void detailWithCrlfAndControlCharsIsSanitizedAndTruncated() {
        Review review = persistRunningReview("sha-report-sanitize");
        ReviewJob job = persistRunningJob(review, 1, "worker-owner");
        String maliciousDetail = "line1\r\n2026-01-01 FORGED INFO line" + "x".repeat(400);

        report(newQueueManager(3), job.getId(), "worker-owner", "LLM_ERROR", maliciousDetail);

        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getLastError()).doesNotContain("\r").doesNotContain("\n");
        assertThat(reloaded.getLastError().length()).isLessThanOrEqualTo(512);

        List<ReviewEvent> events = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(review.getId());
        ReviewEvent retryEvent = events.stream()
                .filter(e -> e.getEventType() == EventType.RETRY && e.getJobId() != null)
                .findFirst().orElseThrow();
        assertThat(retryEvent.getDetails()).doesNotContain("\r").doesNotContain("\n");
        assertThat(retryEvent.getDetails().length()).isLessThanOrEqualTo(500);
    }

    private FailureReportOutcome report(QueueManager queueManager, Long jobId, String workerId, String reason, String detail) {
        return queueManager.reportFailure(jobId, workerId, reason, detail);
    }
}

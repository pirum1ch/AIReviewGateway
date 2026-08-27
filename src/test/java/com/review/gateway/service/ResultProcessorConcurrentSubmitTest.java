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
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewResultRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.SubmitResultCommand;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F02-02 regression (V2, diff chunking): two genuinely concurrent {@code POST /jobs/{id}/result}
 * deliveries for the same job (same owning worker -- this is not an ownership/SR-04 scenario, it is
 * two racing calls that both legitimately passed the {@code RUNNING} check in {@code QueueManager}
 * before either had committed) must not both insert parsed comments or both record a {@code COMPLETED}
 * event. {@code ResultProcessor}'s phase 1 now loads the job under
 * {@code ReviewJobRepository#findByIdForUpdate} (pessimistic {@code SELECT ... FOR UPDATE}), so the
 * second caller blocks until the first's transaction commits, then observes {@code status != RUNNING}
 * and safely no-ops; phase 2 ({@code ChunkCoordinator}) additionally locks the parent review row before
 * inserting any comment (CSR-21), which is the second layer of protection this test exercises.
 *
 * <p>{@code @Transactional(NOT_SUPPORTED)}: real, separately-committed fixture rows are required for
 * the {@code REQUIRES_NEW} phases to see them, and genuine concurrency additionally requires two
 * distinct physical connections/transactions, which a single ambient per-test transaction would
 * prevent.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResultProcessorConcurrentSubmitTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewChunkRepository reviewChunkRepository;
    @Autowired
    private ReviewResultRepository reviewResultRepository;
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

    private ResultProcessor newResultProcessor() {
        EventService eventService = new EventService(reviewEventRepository, new TextSanitizer());
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        CommentParser commentParser = new CommentParser(new GatewayProperties(), new MetricsCounters());
        GatewayProperties properties = new GatewayProperties();
        ChunkCoordinator chunkCoordinator = new ChunkCoordinator(reviewRepository, reviewJobRepository,
                reviewChunkRepository, reviewCommentRepository, stateMachine, jobStateMachine, properties, entityManager, transactionManager);
        RetryManager retryManager = new RetryManager(reviewJobRepository, jobStateMachine, chunkCoordinator,
                properties, new TextSanitizer(), entityManager, transactionManager);
        CommentRenderer commentRenderer = new CommentRenderer(commentParser, new TextSanitizer(), properties);
        StructuredResponseParser structuredResponseParser = new StructuredResponseParser(
                commentParser, commentRenderer, new TextSanitizer(), properties, new MetricsCounters());
        return new ResultProcessor(reviewRepository, reviewJobRepository, reviewChunkRepository, reviewResultRepository,
                commentParser, structuredResponseParser, jobStateMachine, chunkCoordinator, retryManager,
                new MetricsCounters(), properties, entityManager, transactionManager);
    }

    @Test
    void concurrentDuplicateSubmissionsOnlyCompleteTheReviewOnce() throws Exception {
        Review review = new Review(1L, 1L, "sha-concurrent", "base", "v1", 10);
        review.setStatus(ReviewStatus.RUNNING);
        review.setAttempts(1);
        review = reviewRepository.saveAndFlush(review);
        Long reviewId = review.getId();

        Backend backend = backendRepository.saveAndFlush(new Backend("backend-concurrent", "https://backend-concurrent.local", "model", 1));
        ReviewJob job = new ReviewJob(reviewId, backend.getId(), "worker-1");
        job.setStatus(JobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job = reviewJobRepository.saveAndFlush(job);
        Long jobId = job.getId();
        Long backendId = job.getBackendId();

        ResultProcessor processorA = newResultProcessor();
        ResultProcessor processorB = newResultProcessor();

        CountDownLatch startLatch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReviewStatus> futureA = executor.submit(() -> {
                startLatch.countDown();
                startLatch.await(10, TimeUnit.SECONDS);
                return processorA.process(reviewId, jobId, "worker-1", backendId,
                        new SubmitResultCommand("[{\"comment\":\"finding from A\"}]", 1, 1, 10L, "model-x", null));
            });
            Future<ReviewStatus> futureB = executor.submit(() -> {
                startLatch.countDown();
                startLatch.await(10, TimeUnit.SECONDS);
                return processorB.process(reviewId, jobId, "worker-1", backendId,
                        new SubmitResultCommand("[{\"comment\":\"finding from B\"}]", 1, 1, 10L, "model-x", null));
            });

            ReviewStatus resultA = futureA.get(15, TimeUnit.SECONDS);
            ReviewStatus resultB = futureB.get(15, TimeUnit.SECONDS);

            assertThat(resultA).isEqualTo(ReviewStatus.COMPLETED);
            assertThat(resultB).isEqualTo(ReviewStatus.COMPLETED);
        } finally {
            executor.shutdownNow();
        }

        // The decisive assertions: exactly ONE of the two submissions' comments made it in, and the
        // Review was completed exactly once -- never both, regardless of which one "won" the race.
        long commentCount = reviewCommentRepository.countByReviewId(reviewId);
        assertThat(commentCount).as("only one submission's comment(s) may be persisted").isEqualTo(1);

        // V2 (diff chunking): a COMPLETED event is now recorded at TWO levels -- once per job (chunk_index/
        // job_id set, via JobStateMachine) and once for the parent Review's derived status (chunk_index/
        // job_id null, via ChunkCoordinator -> StateMachine). Exactly one job exists here, so exactly one
        // job-level COMPLETED event is expected; the race-safety property this test actually exercises is
        // that the PARENT-level (review) COMPLETED transition itself only ever happens once, regardless of
        // which of the two racing submissions "won".
        List<ReviewEvent> completedEvents = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(reviewId).stream()
                .filter(event -> event.getEventType() == EventType.COMPLETED)
                .toList();
        List<ReviewEvent> reviewLevelCompletedEvents = completedEvents.stream()
                .filter(event -> event.getJobId() == null)
                .toList();
        assertThat(reviewLevelCompletedEvents)
                .as("the parent Review's RUNNING -> COMPLETED transition must happen exactly once")
                .hasSize(1);
        assertThat(completedEvents)
                .as("exactly one job-level + one review-level COMPLETED event for this single-chunk Review")
                .hasSize(2);

        Review reloaded = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.COMPLETED);
    }
}

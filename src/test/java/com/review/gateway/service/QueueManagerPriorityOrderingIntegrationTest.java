package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewChunk;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.BackendStatus;
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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fills a coverage gap in {@link QueueManagerIntegrationTest}: that suite proves claim's capacity/
 * status/payload behavior but never exercises the ordering guarantee itself (architecture §5 step 2:
 * {@code ORDER BY priority DESC, created_at ASC}, now on {@code review_jobs} as of V2 diff chunking).
 *
 * <p>{@code @Transactional(NOT_SUPPORTED)}: see {@code QueueManagerIntegrationTest}'s javadoc for why.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QueueManagerPriorityOrderingIntegrationTest extends AbstractPostgresIntegrationTest {

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

    private QueueManager newQueueManager() {
        EventService eventService = new EventService(reviewEventRepository, new TextSanitizer());
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        GatewayProperties properties = new GatewayProperties();
        BackendDispatcher backendDispatcher = new BackendDispatcher(backendRepository, reviewJobRepository, properties);
        ChunkCoordinator chunkCoordinator = new ChunkCoordinator(reviewRepository, reviewJobRepository,
                reviewChunkRepository, reviewCommentRepository, stateMachine, jobStateMachine, properties,
                entityManager, transactionManager);
        ChunkContextRenderer chunkContextRenderer = new ChunkContextRenderer(properties, new TextSanitizer());
        PromptMessageFormatter promptMessageFormatter = new PromptMessageFormatter(properties, new PromptAssembler(properties, new DiffSizeValidator(properties)));
        RetryManager retryManager = new RetryManager(reviewJobRepository, jobStateMachine, chunkCoordinator,
                properties, new TextSanitizer(), entityManager, transactionManager);
        return new QueueManager(reviewRepository, reviewJobRepository, reviewChunkRepository,
                reviewPromptSectionRepository, backendDispatcher, jobStateMachine, chunkCoordinator, eventService,
                Mockito.mock(ResultProcessor.class), chunkContextRenderer, promptMessageFormatter, retryManager,
                new TextSanitizer(), new MetricsCounters(), entityManager, transactionManager);
    }

    private Review persistQueuedReview(long mrId, String headSha, int priority, Instant createdAt) {
        Review review = new Review(1L, mrId, headSha, "base", "v1", priority);
        review.setStatus(ReviewStatus.QUEUED);
        review = reviewRepository.saveAndFlush(review);
        reviewInputRepository.saveAndFlush(new ReviewInput(review.getId(), "diff-" + headSha, "v1", headSha, "base", 10));
        ReviewChunk chunk = reviewChunkRepository.saveAndFlush(
                new ReviewChunk(review.getId(), 0, 1, "diff-" + headSha, 10, 0, "[]"));
        ReviewJob job = reviewJobRepository.saveAndFlush(new ReviewJob(review.getId(), chunk.getId(), 0, priority, null, null));

        // created_at is DB-defaulted (now()) at insert time; force the intended ordering with a direct
        // update (in its own genuine transaction, since NOT_SUPPORTED disables the ambient one) so the
        // test can express "arrived earlier" deterministically regardless of wall-clock granularity. As
        // of V2 the claim query orders review_jobs.created_at, not reviews.created_at.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("UPDATE review_jobs SET created_at = ?1 WHERE id = ?2")
                        .setParameter(1, createdAt)
                        .setParameter(2, job.getId())
                        .executeUpdate());
        return review;
    }

    private void persistBackend(String name, int capacity) {
        Backend backend = new Backend(name, "https://" + name + ".local", "model-x", capacity);
        backend.setStatus(BackendStatus.ACTIVE);
        backendRepository.saveAndFlush(backend);
    }

    @Test
    void claimPicksTheHighestPriorityReviewRegardlessOfInsertionOrder() {
        persistBackend("prio-backend-a", 1);
        Instant now = Instant.now();
        persistQueuedReview(200L, "sha-low-prio", 5, now);
        Review highPriority = persistQueuedReview(201L, "sha-high-prio", 50, now);
        persistQueuedReview(202L, "sha-mid-prio", 10, now);

        QueueManager queueManager = newQueueManager();
        ClaimedJob claimed = queueManager.claim("prio-backend-a", "worker-1").orElseThrow();

        assertThat(claimed.reviewId()).isEqualTo(highPriority.getId());
    }

    @Test
    void withEqualPriorityClaimPicksTheOldestCreatedAtFirst() {
        persistBackend("prio-backend-b", 1);
        Instant older = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant newer = Instant.now();
        persistQueuedReview(210L, "sha-newer", 10, newer);
        Review olderReview = persistQueuedReview(211L, "sha-older", 10, older);

        QueueManager queueManager = newQueueManager();
        ClaimedJob claimed = queueManager.claim("prio-backend-b", "worker-1").orElseThrow();

        assertThat(claimed.reviewId()).isEqualTo(olderReview.getId());
    }

    @Test
    void secondClaimAfterFirstGetsTheNextHighestPriorityRemainingReview() {
        persistBackend("prio-backend-c", 2);
        Instant now = Instant.now();
        Review high = persistQueuedReview(220L, "sha-c-high", 30, now);
        Review mid = persistQueuedReview(221L, "sha-c-mid", 20, now);
        persistQueuedReview(222L, "sha-c-low", 10, now);

        QueueManager queueManager = newQueueManager();
        ClaimedJob first = queueManager.claim("prio-backend-c", "worker-1").orElseThrow();
        ClaimedJob second = queueManager.claim("prio-backend-c", "worker-2").orElseThrow();

        assertThat(first.reviewId()).isEqualTo(high.getId());
        assertThat(second.reviewId()).isEqualTo(mid.getId());
    }
}

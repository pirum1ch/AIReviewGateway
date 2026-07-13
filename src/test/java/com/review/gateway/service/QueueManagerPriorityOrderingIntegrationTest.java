package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.ClaimedJob;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fills a coverage gap in {@link QueueManagerIntegrationTest}: that suite proves claim's capacity/
 * status/payload behavior but never exercises the ordering guarantee itself (architecture §5 step 2:
 * {@code ORDER BY priority DESC, created_at ASC}). {@code ReviewRepositoryConcurrentClaimTest}
 * (feature/01-data-model) proves {@code SKIP LOCKED} concurrency safety at the raw-SQL level; this
 * test proves the same ordering holds end-to-end through {@link QueueManager#claim}.
 */
class QueueManagerPriorityOrderingIntegrationTest extends AbstractPostgresIntegrationTest {

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

    private QueueManager newQueueManager() {
        EventService eventService = new EventService(reviewEventRepository);
        StateMachine stateMachine = new StateMachine(eventService);
        BackendDispatcher backendDispatcher = new BackendDispatcher(backendRepository, reviewJobRepository);
        return new QueueManager(reviewRepository, reviewJobRepository, reviewInputRepository,
                backendDispatcher, stateMachine, eventService, Mockito.mock(ResultProcessor.class));
    }

    private Review persistQueuedReview(long mrId, String headSha, int priority, Instant createdAt) {
        Review review = new Review(1L, mrId, headSha, "base", "v1", priority);
        review.setStatus(ReviewStatus.QUEUED);
        Review saved = entityManager.persistFlushFind(review);
        entityManager.persistAndFlush(new ReviewInput(saved.getId(), "diff-" + headSha, "v1", headSha, "base", 10));
        // createdAt is DB-defaulted (now()) at insert time; force the intended ordering with a direct update
        // so the test can express "arrived earlier" deterministically regardless of wall-clock granularity.
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE reviews SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, createdAt)
                .setParameter(2, saved.getId())
                .executeUpdate();
        entityManager.getEntityManager().clear();
        return saved;
    }

    private void persistBackend(String name, int capacity) {
        Backend backend = new Backend(name, "https://" + name + ".local", "model-x", capacity);
        backend.setStatus(BackendStatus.ACTIVE);
        entityManager.persistFlushFind(backend);
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

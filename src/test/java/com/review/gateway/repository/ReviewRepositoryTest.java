package com.review.gateway.repository;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.model.Review;
import com.review.gateway.model.enums.ReviewStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRepositoryTest extends AbstractPostgresIntegrationTest {

    private static final Set<ReviewStatus> ACTIVE_STATUSES = EnumSet.of(
            ReviewStatus.NEW, ReviewStatus.QUEUED, ReviewStatus.RUNNING,
            ReviewStatus.COMPLETED, ReviewStatus.PUBLISHED);

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Review newReview(long projectId, long mrId, String headSha, ReviewStatus status, int priority) {
        Review review = new Review(projectId, mrId, headSha, "base-sha", "v1", priority);
        review.setStatus(status);
        return review;
    }

    /** Backdates {@code created_at} via a native update, bypassing the {@code @PrePersist} clock. */
    private void backdate(Long reviewId, Instant createdAt) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE reviews SET created_at = :ts WHERE id = :id")
                .setParameter("ts", createdAt)
                .setParameter("id", reviewId)
                .executeUpdate();
        entityManager.getEntityManager().clear();
    }

    // NOTE: PostgreSQL aborts the entire transaction after a constraint violation (subsequent
    // statements fail with "current transaction is aborted" until rollback). @DataJpaTest runs
    // each test method in one transaction, so the "reject duplicate", "lookup finds original", and
    // "allowed again after FAILED" assertions are split into separate test methods below, each
    // with its own clean transaction, rather than chained after the violation is triggered.

    @Test
    void dedupIndexRejectsSecondActiveReviewWithSameKey() {
        Review first = newReview(1L, 10L, "sha-a", ReviewStatus.QUEUED, 10);
        entityManager.persistAndFlush(first);

        // A direct EntityManager.persist bypasses Spring Data's repository-level exception
        // translation (that translation is what turns this into DataIntegrityViolationException
        // when going through a Spring Data repository, per architecture §6/idempotency table).
        // Here we assert directly against the underlying constraint violation raised by Postgres.
        Review duplicate = newReview(1L, 10L, "sha-a", ReviewStatus.QUEUED, 10);
        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @Test
    void dedupLookupFindsExistingActiveReviewForSameKey() {
        Review first = newReview(1L, 11L, "sha-a2", ReviewStatus.QUEUED, 10);
        entityManager.persistAndFlush(first);

        Optional<Review> found = reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaAndStatusIn(
                1L, 11L, "sha-a2", ACTIVE_STATUSES);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(first.getId());
    }

    @Test
    void dedupIndexAllowsNewReviewForSameKeyOnceOriginalHasFailed() {
        Review first = newReview(1L, 12L, "sha-a3", ReviewStatus.QUEUED, 10);
        entityManager.persistAndFlush(first);

        first.setStatus(ReviewStatus.FAILED);
        entityManager.persistAndFlush(first);

        Review afterFailure = newReview(1L, 12L, "sha-a3", ReviewStatus.QUEUED, 10);
        Review saved = entityManager.persistFlushFind(afterFailure);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void dedupLookupIgnoresTerminalReviews() {
        Review cancelled = newReview(2L, 20L, "sha-b", ReviewStatus.CANCELLED, 10);
        entityManager.persistAndFlush(cancelled);

        Optional<Review> found = reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaAndStatusIn(
                2L, 20L, "sha-b", ACTIVE_STATUSES);
        assertThat(found).isEmpty();
    }

    @Test
    void claimQueryReturnsHighestPriorityThenOldest() {
        Instant now = Instant.now();

        Review lowPriorityOld = newReview(3L, 30L, "sha-low", ReviewStatus.QUEUED, 5);
        entityManager.persistAndFlush(lowPriorityOld);
        backdate(lowPriorityOld.getId(), now.minus(1, ChronoUnit.HOURS));

        Review highPriorityNewer = newReview(3L, 31L, "sha-high-new", ReviewStatus.QUEUED, 10);
        entityManager.persistAndFlush(highPriorityNewer);
        backdate(highPriorityNewer.getId(), now.minus(5, ChronoUnit.MINUTES));

        Review highPriorityOldest = newReview(3L, 32L, "sha-high-old", ReviewStatus.QUEUED, 10);
        entityManager.persistAndFlush(highPriorityOldest);
        backdate(highPriorityOldest.getId(), now.minus(20, ChronoUnit.MINUTES));

        // First claim: highest priority (10) wins over priority 5; among the two priority-10 rows,
        // the older one (created 20m ago) wins over the newer one (created 5m ago).
        Optional<Long> firstClaim = reviewRepository.findNextQueuedReviewIdForUpdate();
        assertThat(firstClaim).contains(highPriorityOldest.getId());

        // Simulate the claim completing (QUEUED -> RUNNING) so the next call sees a different row.
        Review claimed = entityManager.find(Review.class, highPriorityOldest.getId());
        claimed.setStatus(ReviewStatus.RUNNING);
        entityManager.persistAndFlush(claimed);

        Optional<Long> secondClaim = reviewRepository.findNextQueuedReviewIdForUpdate();
        assertThat(secondClaim).contains(highPriorityNewer.getId());

        Review claimed2 = entityManager.find(Review.class, highPriorityNewer.getId());
        claimed2.setStatus(ReviewStatus.RUNNING);
        entityManager.persistAndFlush(claimed2);

        Optional<Long> thirdClaim = reviewRepository.findNextQueuedReviewIdForUpdate();
        assertThat(thirdClaim).contains(lowPriorityOld.getId());
    }

    @Test
    void claimQueryReturnsEmptyWhenNothingQueued() {
        Review running = newReview(4L, 40L, "sha-running", ReviewStatus.RUNNING, 10);
        entityManager.persistAndFlush(running);

        assertThat(reviewRepository.findNextQueuedReviewIdForUpdate()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = {"QUEUED"}, mode = EnumSource.Mode.EXCLUDE)
    void claimQuerySkipsEveryNonQueuedStatus(ReviewStatus nonQueuedStatus) {
        Review notQueued = newReview(8L, 80L, "sha-not-queued-" + nonQueuedStatus, nonQueuedStatus, 10);
        entityManager.persistAndFlush(notQueued);

        assertThat(reviewRepository.findNextQueuedReviewIdForUpdate()).isEmpty();
    }

    @Test
    void markObsoleteForOtherHeadShasIsIdempotentAndScopedToNonTerminalStatuses() {
        Review stale = newReview(5L, 50L, "sha-old", ReviewStatus.QUEUED, 10);
        entityManager.persistAndFlush(stale);
        Review published = newReview(5L, 50L, "sha-older-published", ReviewStatus.PUBLISHED, 10);
        entityManager.persistAndFlush(published);
        Review otherMr = newReview(5L, 51L, "sha-unrelated", ReviewStatus.QUEUED, 10);
        entityManager.persistAndFlush(otherMr);

        Set<ReviewStatus> obsoletable = EnumSet.of(
                ReviewStatus.NEW, ReviewStatus.QUEUED, ReviewStatus.RUNNING, ReviewStatus.COMPLETED);

        int updated = reviewRepository.markObsoleteForOtherHeadShas(
                5L, 50L, "sha-new", obsoletable, Instant.now());

        assertThat(updated).isEqualTo(1); // only `stale`; PUBLISHED and other-MR rows untouched
        entityManager.getEntityManager().clear();

        assertThat(entityManager.find(Review.class, stale.getId()).getStatus()).isEqualTo(ReviewStatus.OBSOLETE);
        assertThat(entityManager.find(Review.class, published.getId()).getStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(entityManager.find(Review.class, otherMr.getId()).getStatus()).isEqualTo(ReviewStatus.QUEUED);

        // Idempotent: running it again touches nothing further.
        int updatedAgain = reviewRepository.markObsoleteForOtherHeadShas(
                5L, 50L, "sha-new", obsoletable, Instant.now());
        assertThat(updatedAgain).isZero();
    }

    @Test
    void findByStatusReturnsPublishRetryCandidatesOldestFirst() {
        Review completedNewer = newReview(6L, 60L, "sha-c1", ReviewStatus.COMPLETED, 10);
        entityManager.persistAndFlush(completedNewer);
        backdate(completedNewer.getId(), Instant.now().minus(1, ChronoUnit.MINUTES));

        Review completedOlder = newReview(6L, 61L, "sha-c2", ReviewStatus.COMPLETED, 10);
        entityManager.persistAndFlush(completedOlder);
        backdate(completedOlder.getId(), Instant.now().minus(1, ChronoUnit.HOURS));

        Review published = newReview(6L, 62L, "sha-c3", ReviewStatus.PUBLISHED, 10);
        entityManager.persistAndFlush(published);

        List<Review> candidates = reviewRepository.findByStatusOrderByCreatedAtAsc(ReviewStatus.COMPLETED);

        assertThat(candidates).extracting(Review::getId)
                .containsExactly(completedOlder.getId(), completedNewer.getId());
    }

    @Test
    void countByStatusGroupedAggregatesCorrectly() {
        entityManager.persistAndFlush(newReview(7L, 70L, "sha-x1", ReviewStatus.QUEUED, 10));
        entityManager.persistAndFlush(newReview(7L, 71L, "sha-x2", ReviewStatus.QUEUED, 10));
        entityManager.persistAndFlush(newReview(7L, 72L, "sha-x3", ReviewStatus.RUNNING, 10));

        List<ReviewRepository.StatusCount> counts = reviewRepository.countByStatusGrouped();

        long queuedCount = counts.stream()
                .filter(c -> c.getStatus() == ReviewStatus.QUEUED)
                .mapToLong(ReviewRepository.StatusCount::getTotal)
                .sum();
        long runningCount = counts.stream()
                .filter(c -> c.getStatus() == ReviewStatus.RUNNING)
                .mapToLong(ReviewRepository.StatusCount::getTotal)
                .sum();

        assertThat(queuedCount).isEqualTo(2);
        assertThat(runningCount).isEqualTo(1);
    }
}

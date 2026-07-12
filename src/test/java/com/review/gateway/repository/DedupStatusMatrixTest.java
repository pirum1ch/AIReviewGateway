package com.review.gateway.repository;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.model.Review;
import com.review.gateway.model.enums.ReviewStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive dedup-key matrix (req. 1.5, architecture §3/{@code ux_reviews_dedup_active}):
 * every one of the 5 "active" statuses must block a same-key duplicate at the DB level, every one
 * of the 3 terminal statuses must allow a fresh Review for the same key, and a key with several
 * terminal predecessors plus exactly one new active Review must be consistent (unique index still
 * satisfied, dedup lookup returns only the active row).
 *
 * <p>Each {@code project_id}/{@code merge_request_id} pair used below is unique per test method
 * (via {@link #uniqueMrId()}) purely so parameterized invocations sharing this JVM/test class never
 * collide with each other; that is a test-isolation detail, not a behavior under test.
 */
class DedupStatusMatrixTest extends AbstractPostgresIntegrationTest {

    private static final Set<ReviewStatus> ACTIVE_STATUSES = EnumSet.of(
            ReviewStatus.NEW, ReviewStatus.QUEUED, ReviewStatus.RUNNING,
            ReviewStatus.COMPLETED, ReviewStatus.PUBLISHED);

    private static final Set<ReviewStatus> TERMINAL_STATUSES = EnumSet.of(
            ReviewStatus.FAILED, ReviewStatus.CANCELLED, ReviewStatus.OBSOLETE);

    private static final AtomicLong MR_SEQUENCE = new AtomicLong(1000);

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private TestEntityManager entityManager;

    private long uniqueMrId() {
        return MR_SEQUENCE.incrementAndGet();
    }

    private Review newReview(long projectId, long mrId, String headSha, ReviewStatus status) {
        Review review = new Review(projectId, mrId, headSha, "base-sha", "v1", 10);
        review.setStatus(status);
        return review;
    }

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = {"NEW", "QUEUED", "RUNNING", "COMPLETED", "PUBLISHED"})
    void everyActiveStatusBlocksADuplicateForTheSameKey(ReviewStatus activeStatus) {
        long mrId = uniqueMrId();
        Review original = newReview(42L, mrId, "sha-active", activeStatus);
        entityManager.persistAndFlush(original);

        // Dedup lookup must resolve to the existing active row BEFORE we trigger the constraint
        // violation below: Postgres aborts the whole transaction on a violation, so no further
        // statement (including a read) can run afterward in this same @DataJpaTest transaction.
        Optional<Review> found = reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaAndStatusIn(
                42L, mrId, "sha-active", ACTIVE_STATUSES);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(original.getId());

        Review duplicate = newReview(42L, mrId, "sha-active", ReviewStatus.NEW);
        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ReviewStatus.class, names = {"FAILED", "CANCELLED", "OBSOLETE"})
    void everyTerminalStatusAllowsAFreshReviewForTheSameKey(ReviewStatus terminalStatus) {
        long mrId = uniqueMrId();
        Review predecessor = newReview(43L, mrId, "sha-terminal", terminalStatus);
        entityManager.persistAndFlush(predecessor);

        Review fresh = newReview(43L, mrId, "sha-terminal", ReviewStatus.QUEUED);
        Review saved = entityManager.persistFlushFind(fresh);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getId()).isNotEqualTo(predecessor.getId());

        Optional<Review> found = reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaAndStatusIn(
                43L, mrId, "sha-terminal", ACTIVE_STATUSES);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }

    @org.junit.jupiter.api.Test
    void allThreeTerminalPredecessorsCoexistWithExactlyOneNewActiveReview() {
        long mrId = uniqueMrId();

        Review failed = newReview(44L, mrId, "sha-multi-terminal", ReviewStatus.FAILED);
        entityManager.persistAndFlush(failed);
        Review cancelled = newReview(44L, mrId, "sha-multi-terminal", ReviewStatus.CANCELLED);
        entityManager.persistAndFlush(cancelled);
        Review obsolete = newReview(44L, mrId, "sha-multi-terminal", ReviewStatus.OBSOLETE);
        entityManager.persistAndFlush(obsolete);

        // Three terminal rows for the identical key coexist fine (excluded from the partial index).
        Review active = newReview(44L, mrId, "sha-multi-terminal", ReviewStatus.QUEUED);
        Review savedActive = entityManager.persistFlushFind(active);
        assertThat(savedActive.getId()).isNotNull();

        Optional<Review> found = reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaAndStatusIn(
                44L, mrId, "sha-multi-terminal", ACTIVE_STATUSES);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(savedActive.getId());

        // A second active row for the same key is still rejected even with 3 terminal siblings
        // present. This must be the last statement in the test: Postgres aborts the whole
        // transaction once the violation fires.
        Review secondActive = newReview(44L, mrId, "sha-multi-terminal", ReviewStatus.NEW);
        assertThatThrownBy(() -> entityManager.persistAndFlush(secondActive))
                .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }
}

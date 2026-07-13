package com.review.gateway.repository;

import com.review.gateway.model.Review;
import com.review.gateway.model.enums.ReviewStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@link Review} aggregate root — the queue owner and single source of truth
 * for lifecycle status.
 *
 * <p>All native/JPQL queries here use bound (named) parameters exclusively (SR-13); none are built
 * by string concatenation.
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    /**
     * Dedup lookup: finds an existing Review for the {@code (projectId, mergeRequestId, headSha)}
     * key that is still in one of the "active" statuses (mirrors {@code ux_reviews_dedup_active}).
     * If found, the caller must not create a new Review and should return this one's id instead
     * (req. 1.5).
     */
    Optional<Review> findByProjectIdAndMergeRequestIdAndHeadShaAndStatusIn(
            Long projectId, Long mergeRequestId, String headSha, Collection<ReviewStatus> activeStatuses);

    /**
     * Claims the next queued Review: highest {@code priority} first, then oldest {@code createdAt}
     * (FIFO within the same priority). Uses {@code FOR UPDATE SKIP LOCKED} so concurrent claimers
     * never contend on the same row — each queued Review is handed to exactly one caller (req.
     * 1.3). This must run inside a short, dedicated transaction (service layer, {@code
     * REQUIRES_NEW}); the row lock is released as soon as that transaction commits.
     *
     * <p>Native query is required because JPQL has no {@code SKIP LOCKED} support.
     */
    @Query(value = """
            SELECT r.id
            FROM reviews r
            WHERE r.status = 'QUEUED'
            ORDER BY r.priority DESC, r.created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    Optional<Long> findNextQueuedReviewIdForUpdate();

    /**
     * Marks every non-terminal Review of the same (project, MR) that does NOT match the new
     * {@code headSha} as {@link ReviewStatus#OBSOLETE}. Idempotent: re-running it after a crash or
     * concurrently only touches rows that are still in one of {@code obsoletableStatuses} (req.
     * 1.5 — only non-PUBLISHED reviews become OBSOLETE).
     *
     * @return number of rows updated
     */
    @Modifying
    @Query("""
            UPDATE Review r
            SET r.status = com.review.gateway.model.enums.ReviewStatus.OBSOLETE, r.updatedAt = :now
            WHERE r.projectId = :projectId
              AND r.mergeRequestId = :mergeRequestId
              AND r.headSha <> :newHeadSha
              AND r.status IN :obsoletableStatuses
            """)
    int markObsoleteForOtherHeadShas(@Param("projectId") Long projectId,
                                      @Param("mergeRequestId") Long mergeRequestId,
                                      @Param("newHeadSha") String newHeadSha,
                                      @Param("obsoletableStatuses") Collection<ReviewStatus> obsoletableStatuses,
                                      @Param("now") Instant now);

    /**
     * Publish-retry candidates: reviews that finished successfully but are not yet fully published
     * (comments still pending, or GitLab was unavailable on a previous attempt).
     */
    List<Review> findByStatusOrderByCreatedAtAsc(ReviewStatus status);

    /**
     * Candidates for the OBSOLETE sweep (req. 1.5): every Review of the same (project, MR) whose
     * {@code headSha} differs from the newly-arrived one and whose status is still one of
     * {@code obsoletableStatuses}. Unlike {@link #markObsoleteForOtherHeadShas}, this returns the
     * managed entities themselves (not just a row count) so {@code ReviewService} can drive each one
     * through {@code StateMachine} individually — which is what produces one {@code OBSOLETE}
     * {@code review_events} row per affected Review (req. 1.11), rather than a single silent bulk
     * UPDATE with no per-row audit trail.
     */
    List<Review> findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusIn(
            Long projectId, Long mergeRequestId, String headSha, Collection<ReviewStatus> obsoletableStatuses);

    /**
     * F02-02 fix: loads a Review under a row-level {@code SELECT ... FOR UPDATE} lock (SR-06 spirit —
     * serializes completion instead of a lease token, since the frozen V1 schema has no lease column
     * to add). {@code ResultProcessor}'s completion/fail phase uses this instead of {@code findById} so
     * that two genuinely concurrent {@code submitResult} calls for the same Review cannot both insert
     * comments and both transition {@code RUNNING -> COMPLETED}: the second caller's lock acquisition
     * blocks until the first's {@code REQUIRES_NEW} transaction commits, at which point it re-observes
     * the Review as no longer {@code RUNNING} and safely no-ops.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Review r WHERE r.id = :id")
    Optional<Review> findByIdForUpdate(@Param("id") Long id);

    /**
     * Aggregate counts per status, backing {@code GET /metrics}.
     */
    @Query("SELECT r.status AS status, COUNT(r) AS total FROM Review r GROUP BY r.status")
    List<StatusCount> countByStatusGrouped();

    /** Projection for {@link #countByStatusGrouped()}. */
    interface StatusCount {
        ReviewStatus getStatus();

        Long getTotal();
    }
}

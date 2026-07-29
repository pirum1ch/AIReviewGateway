package com.review.gateway.repository;

import com.review.gateway.model.Review;
import com.review.gateway.model.enums.ReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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
     *
     * <p><b>Deliberately unlocked</b> (F-DC-03 fix): this used to carry {@code @Lock(PESSIMISTIC_WRITE)}
     * (a plain {@code FOR UPDATE}), but that conflicts with the {@code FOR KEY SHARE} every child-row
     * {@code INSERT} (`review_events`/`review_results`/`review_comments`/`review_jobs`, all FK-
     * referencing {@code reviews}) implicitly takes on this same row via PostgreSQL's referential-
     * integrity trigger — reproduced as a real {@code deadlock detected} (SQLSTATE 40P01) against a
     * child-first writer (e.g. {@code RetryManager}) holding a job-row lock and inserting an event.
     * {@code ReviewService.sweepObsolete} now takes the row-level lock explicitly, one row at a time in
     * this {@code ORDER BY id} order, via {@link #findByIdForNoKeyUpdate} (CSR-18 determinism preserved:
     * the ordering here is what the caller iterates and locks in).
     */
    List<Review> findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(
            Long projectId, Long mergeRequestId, String headSha, Collection<ReviewStatus> obsoletableStatuses);

    /**
     * F-DC-03 fix (supersedes the old {@code findByIdForUpdate}, a plain {@code FOR UPDATE}): locks the
     * Review row with PostgreSQL's {@code FOR NO KEY UPDATE} instead. {@code FOR NO KEY UPDATE} still
     * mutually excludes every other writer that also wants to mutate/delete this row (other callers of
     * this same method, or anything using {@code FOR UPDATE}) — but, critically, it does **not**
     * conflict with {@code FOR KEY SHARE}, which is exactly the lock mode PostgreSQL's own referential-
     * integrity trigger takes on {@code reviews} for every child-row insert (`review_events` /
     * `review_results` / `review_comments` / `review_jobs`, all {@code REFERENCES reviews(id)}). Using
     * {@code FOR UPDATE} here (as a prior version did) let a parent-lock holder (this method) and a
     * child-first writer (e.g. {@code RetryManager}, which locks a job row then inserts an event) form a
     * genuine two-cycle deadlock, reproduced on real PostgreSQL (SQLSTATE 40P01, F-DC-03). Switching to
     * {@code FOR NO KEY UPDATE} removes that edge entirely while preserving every actual invariant this
     * lock exists for (mutual exclusion between concurrent parent-status writers such as
     * {@code ReviewService.cancel}/{@code sweepObsolete} and {@code ChunkCoordinator}).
     *
     * <p>A native query is required: JPA's {@code LockModeType} has no {@code FOR NO KEY UPDATE}
     * equivalent (only {@code FOR UPDATE}-strength pessimistic write locks are standardized).
     */
    @Query(value = "SELECT * FROM reviews WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    Optional<Review> findByIdForNoKeyUpdate(@Param("id") Long id);

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

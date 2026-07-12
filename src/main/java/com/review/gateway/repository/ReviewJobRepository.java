package com.review.gateway.repository;

import com.review.gateway.model.ReviewJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ReviewJob} — the current-execution record (1:1 with {@code reviews}).
 * Capacity and liveness are always derived by joining back to {@code reviews.status}; there is no
 * separate running-jobs counter (req. 1.6).
 */
public interface ReviewJobRepository extends JpaRepository<ReviewJob, Long> {

    Optional<ReviewJob> findByReviewId(Long reviewId);

    /**
     * Number of jobs currently {@code RUNNING} (per {@code reviews.status}) on the given backend.
     * Used by {@code BackendDispatcher} to enforce {@code backends.capacity} before a claim
     * succeeds (req. 1.6, architecture §5 step 1).
     */
    @Query(value = """
            SELECT count(*)
            FROM review_jobs j
            JOIN reviews r ON r.id = j.review_id
            WHERE j.backend_id = :backendId
              AND r.status = 'RUNNING'
            """, nativeQuery = true)
    long countRunningJobsForBackend(@Param("backendId") Long backendId);

    /**
     * Review ids whose job is {@code RUNNING} but has missed its heartbeat window — candidates for
     * {@code HeartbeatChecker}'s stuck-job sweep (req. 2.7). A missing heartbeat ({@code NULL}) is
     * treated as stale too, so a job that crashed before its first ping is still reclaimed.
     */
    @Query(value = """
            SELECT j.review_id
            FROM review_jobs j
            JOIN reviews r ON r.id = j.review_id
            WHERE r.status = 'RUNNING'
              AND (j.heartbeat_at IS NULL OR j.heartbeat_at < :cutoff)
            """, nativeQuery = true)
    List<Long> findReviewIdsWithStaleHeartbeat(@Param("cutoff") Instant cutoff);

    /**
     * Review ids whose job is {@code RUNNING} and has exceeded the hard max-duration backstop
     * ({@code gateway.job.max-duration}), regardless of heartbeat freshness.
     */
    @Query(value = """
            SELECT j.review_id
            FROM review_jobs j
            JOIN reviews r ON r.id = j.review_id
            WHERE r.status = 'RUNNING'
              AND j.started_at IS NOT NULL
              AND j.started_at < :cutoff
            """, nativeQuery = true)
    List<Long> findReviewIdsExceedingMaxDuration(@Param("cutoff") Instant cutoff);
}

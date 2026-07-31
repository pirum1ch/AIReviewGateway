package com.review.gateway.repository;

import com.review.gateway.model.ReviewJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ReviewJob} — <b>the queue owner as of V2</b> (diff chunking): 1:N per
 * review, one row per chunk, {@code status}/{@code priority}/{@code attempts} live here. Capacity and
 * liveness are derived purely from {@code review_jobs.status} — no separate running-jobs counter and,
 * as of V2, no join back to {@code reviews} is needed for any of these queries (req. 1.6).
 */
public interface ReviewJobRepository extends JpaRepository<ReviewJob, Long> {

    List<ReviewJob> findByReviewIdOrderByChunkIndexAsc(Long reviewId);

    Optional<ReviewJob> findByReviewIdAndChunkIndex(Long reviewId, Integer chunkIndex);

    /**
     * CSR-17/CSR-18: loads a job row under a row-level {@code SELECT ... FOR UPDATE} lock. Used by
     * {@code QueueManager}'s claim path (job-row-only lock, no parent lock) and by
     * {@code RetryManager} (job-row lock taken first, parent updated independently afterward — never
     * while still holding this lock).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM ReviewJob j WHERE j.id = :id")
    Optional<ReviewJob> findByIdForUpdate(@Param("id") Long id);

    /**
     * Claims the next queued job: highest {@code priority} first, then oldest {@code createdAt}
     * (FIFO within the same priority), then lowest {@code chunkIndex} (so a review's own chunks are
     * claimed in file order when several are queued at once). {@code FOR UPDATE SKIP LOCKED} on
     * {@code review_jobs} only — CSR-17: the parent {@code reviews} row is deliberately NOT locked
     * here; ownership afterward is enforced by {@code RUNNING} status + heartbeat, and the parent's
     * derived status is applied by {@code ChunkCoordinator} in a separate, independent transaction.
     */
    @Query(value = """
            SELECT j.id
            FROM review_jobs j
            WHERE j.status = 'QUEUED'
            ORDER BY j.priority DESC, j.created_at ASC, j.chunk_index ASC
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """, nativeQuery = true)
    Optional<Long> findNextQueuedJobIdForUpdate();

    /**
     * Number of jobs currently {@code RUNNING} on the given backend. Used by {@code BackendDispatcher}
     * to enforce {@code backends.capacity} before a claim succeeds (req. 1.6).
     */
    @Query(value = """
            SELECT count(*)
            FROM review_jobs j
            WHERE j.backend_id = :backendId
              AND j.status = 'RUNNING'
            """, nativeQuery = true)
    long countRunningJobsForBackend(@Param("backendId") Long backendId);

    /**
     * Job ids whose status is {@code RUNNING} but has missed its heartbeat window — candidates for
     * {@code HeartbeatChecker}'s stuck-job sweep (req. 2.7). A missing heartbeat ({@code NULL}) is
     * treated as stale too, so a job that crashed before its first ping is still reclaimed.
     */
    @Query(value = """
            SELECT j.id
            FROM review_jobs j
            WHERE j.status = 'RUNNING'
              AND (j.heartbeat_at IS NULL OR j.heartbeat_at < :cutoff)
            """, nativeQuery = true)
    List<Long> findJobIdsWithStaleHeartbeat(@Param("cutoff") Instant cutoff);

    /**
     * Job ids whose status is {@code RUNNING} and has exceeded the hard max-duration backstop
     * ({@code gateway.job.max-duration}), regardless of heartbeat freshness.
     */
    @Query(value = """
            SELECT j.id
            FROM review_jobs j
            WHERE j.status = 'RUNNING'
              AND j.started_at IS NOT NULL
              AND j.started_at < :cutoff
            """, nativeQuery = true)
    List<Long> findJobIdsExceedingMaxDuration(@Param("cutoff") Instant cutoff);

    /**
     * Every non-terminal sibling job of {@code reviewId} other than {@code excludingChunkIndex} — used
     * for sibling-cancellation-on-permanent-failure (parent-then-child propagation, CSR-17).
     */
    @Query("""
            SELECT j FROM ReviewJob j
            WHERE j.reviewId = :reviewId
              AND j.chunkIndex <> :excludingChunkIndex
              AND j.status IN (com.review.gateway.model.enums.JobStatus.QUEUED, com.review.gateway.model.enums.JobStatus.RUNNING)
            """)
    List<ReviewJob> findNonTerminalSiblings(@Param("reviewId") Long reviewId, @Param("excludingChunkIndex") Integer excludingChunkIndex);

    /**
     * Every non-terminal job of {@code reviewId} (used by admin cancel / OBSOLETE sweep — parent-then-
     * child propagation, CSR-17: the parent row is locked by the caller before this is used).
     */
    @Query("""
            SELECT j FROM ReviewJob j
            WHERE j.reviewId = :reviewId
              AND j.status IN (com.review.gateway.model.enums.JobStatus.QUEUED, com.review.gateway.model.enums.JobStatus.RUNNING)
            """)
    List<ReviewJob> findNonTerminalJobs(@Param("reviewId") Long reviewId);

    /**
     * Average time (ms) a job waits in {@code QUEUED} before being claimed, over every job that has
     * been claimed at least once. Backs {@code StatisticsService}/{@code GET /metrics}.
     */
    @Query(value = """
            SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (j.claimed_at - j.created_at)) * 1000), 0)
            FROM review_jobs j
            WHERE j.claimed_at IS NOT NULL
            """, nativeQuery = true)
    Double averageQueueWaitMillis();

    /**
     * Average execution time (ms) across every job that has finished at least one run. Backs
     * {@code StatisticsService}/{@code GET /metrics}.
     */
    @Query(value = """
            SELECT COALESCE(AVG(EXTRACT(EPOCH FROM (j.finished_at - j.started_at)) * 1000), 0)
            FROM review_jobs j
            WHERE j.finished_at IS NOT NULL AND j.started_at IS NOT NULL
            """, nativeQuery = true)
    Double averageRunDurationMillis();
}

package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.repository.ReviewJobRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only place retry-vs-fail decisions are made (req. 1.8), operating on a single chunk JOB (V2,
 * diff chunking) — {@code attempts} is already incremented at claim time, so a value strictly below
 * {@code max-attempts} still has another try coming (-&gt; requeue), while a value at or above the
 * limit is exhausted (-&gt; FAILED). Called by {@link TimeoutManager} for heartbeat-timeout and
 * max-duration cases; Worker/Backend never call this directly.
 *
 * <p><b>CSR-18 fix:</b> the pre-V2 version read the parent Review with a plain {@code findById} (no
 * lock) and then did an unconditional write — a lost-update bug (it could overwrite a just-committed
 * COMPLETED). This version locks the <b>job row</b> {@code FOR UPDATE} first, in its own {@code
 * REQUIRES_NEW} transaction, and performs the job's own status write inside that same lock/transaction.
 * That transaction is committed (releasing the job lock) <em>before</em> {@link ChunkCoordinator} is
 * ever asked to lock the parent row — {@link #requeueOrFail} is a plain, non-{@code @Transactional}
 * orchestrating method for exactly this reason: it calls the job-lock-and-update step via {@link
 * TransactionTemplate} (a genuinely separate, already-committed transaction) and only then calls
 * {@code ChunkCoordinator}, which takes its own independent lock on the parent row. No code path here
 * ever holds the job lock while waiting on the parent lock — the parent-then-child-only propagation
 * direction CSR-17 requires.
 */
@Service
public class RetryManager {

    private static final Logger log = LoggerFactory.getLogger(RetryManager.class);

    private final ReviewJobRepository reviewJobRepository;
    private final JobStateMachine jobStateMachine;
    private final ChunkCoordinator chunkCoordinator;
    private final GatewayProperties properties;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public RetryManager(ReviewJobRepository reviewJobRepository,
                         JobStateMachine jobStateMachine,
                         ChunkCoordinator chunkCoordinator,
                         GatewayProperties properties,
                         EntityManager entityManager,
                         PlatformTransactionManager transactionManager) {
        this.reviewJobRepository = reviewJobRepository;
        this.jobStateMachine = jobStateMachine;
        this.chunkCoordinator = chunkCoordinator;
        this.properties = properties;
        this.entityManager = entityManager;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("RetryManager");
    }

    /**
     * Requeues or fails the given job. Idempotent: if it already left {@code RUNNING} (e.g. a
     * concurrent result arrived, or a previous sweep already handled it), this is a silent no-op —
     * safe to call repeatedly or after a Gateway restart.
     */
    public void requeueOrFail(Long jobId, String reason) {
        Long reviewId = requiresNewTransactionTemplate.execute(status -> requeueOrFailJobOnly(jobId, reason));
        if (reviewId != null) {
            // CSR-17: the job-row lock above was released when that transaction committed; this call
            // takes its own, independent lock on the parent row only now.
            chunkCoordinator.recomputeAndApply(reviewId);
        }
    }

    /** @return the reviewId to recompute after this transaction commits, or {@code null} if nothing changed. */
    private Long requeueOrFailJobOnly(Long jobId, String reason) {
        applyLockTimeout();
        ReviewJob job = reviewJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null) {
            log.warn("requeueOrFail called for missing jobId={}", jobId);
            return null;
        }
        if (job.getStatus() != JobStatus.RUNNING) {
            log.debug("Job {} no longer RUNNING ({}), skipping retry/fail sweep", jobId, job.getStatus());
            return null;
        }

        String workerId = job.getWorkerId();
        Long backendId = job.getBackendId();
        int maxAttempts = properties.getRetry().getMaxAttempts();

        if (job.getAttempts() >= maxAttempts) {
            jobStateMachine.transition(job, JobStatus.FAILED, EventType.FAILED, workerId, backendId,
                    reason + " (attempts exhausted: " + job.getAttempts() + "/" + maxAttempts + ")");
            log.info("Job {} (reviewId={}, chunkIndex={}) FAILED: {} (attempts {}/{})",
                    job.getId(), job.getReviewId(), job.getChunkIndex(), reason, job.getAttempts(), maxAttempts);
        } else {
            jobStateMachine.transition(job, JobStatus.QUEUED, EventType.RETRY, workerId, backendId,
                    reason + " (attempt " + job.getAttempts() + "/" + maxAttempts + ")");
            log.info("Job {} (reviewId={}, chunkIndex={}) requeued for retry: {} (attempt {}/{})",
                    job.getId(), job.getReviewId(), job.getChunkIndex(), reason, job.getAttempts(), maxAttempts);
        }
        reviewJobRepository.save(job);
        return job.getReviewId();
    }

    /**
     * CSR-17: bounds how long this transaction can wait for the job-row lock, so a lock wait can never
     * pin a Hikari connection indefinitely (pool size is 20).
     */
    private void applyLockTimeout() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
    }
}

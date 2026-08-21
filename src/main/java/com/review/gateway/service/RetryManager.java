package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.service.dto.RequeueOutcome;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Timestamp;

/**
 * The only place retry-vs-fail decisions are made (req. 1.8), operating on a single chunk JOB (V2,
 * diff chunking) — {@code attempts} is already incremented at claim time, so a value strictly below
 * {@code max-attempts} still has another try coming (-&gt; requeue), while a value at or above the
 * limit is exhausted (-&gt; FAILED). Called by {@link TimeoutManager} for heartbeat-timeout and
 * max-duration cases, and by {@link QueueManager#reportFailure} for Worker-reported failures (Worker
 * Observability &amp; Claim Latency, WOC-26/27/28).
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
 *
 * <p><b>{@code review_jobs.last_error} grammar (WOC-29/WOR-04):</b> written on <em>both</em> branches
 * (requeue and fail), for sweep-originated and worker-reported calls alike, as:
 * <pre>
 * &lt;reason&gt; (attempt X/Y | attempts exhausted: X/Y)
 * </pre>
 * where {@code reason} is always caller-supplied but, for the worker-reported path, is itself composed
 * by {@code QueueManager.reportFailure} as a Gateway-emitted constant prefix ({@code "worker-reported:
 * reason=<CODE>"}) followed optionally by {@code "; detail=<sanitized>"} — the sanitized, Worker-supplied
 * free text. The attempt-count suffix appended here is always a Gateway constant, appended last, so a
 * crafted {@code detail} value can never make the stored row start with anything other than the
 * Gateway-controlled prefix (WOT-12: the audit-trail origin discriminator is not forgeable).
 */
@Service
public class RetryManager {

    private static final Logger log = LoggerFactory.getLogger(RetryManager.class);

    /** Defense-in-depth cap for {@code review_jobs.last_error} (WOC-29), independent of the audit trail's own cap. */
    private static final int MAX_LAST_ERROR_LENGTH = 512;

    private final ReviewJobRepository reviewJobRepository;
    private final JobStateMachine jobStateMachine;
    private final ChunkCoordinator chunkCoordinator;
    private final GatewayProperties properties;
    private final TextSanitizer textSanitizer;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public RetryManager(ReviewJobRepository reviewJobRepository,
                         JobStateMachine jobStateMachine,
                         ChunkCoordinator chunkCoordinator,
                         GatewayProperties properties,
                         TextSanitizer textSanitizer,
                         EntityManager entityManager,
                         PlatformTransactionManager transactionManager) {
        this.reviewJobRepository = reviewJobRepository;
        this.jobStateMachine = jobStateMachine;
        this.chunkCoordinator = chunkCoordinator;
        this.properties = properties;
        this.textSanitizer = textSanitizer;
        this.entityManager = entityManager;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("RetryManager");
    }

    /**
     * Sweep-originated entry point (no ownership expectation): {@link TimeoutManager}'s two call sites.
     * Thin delegate over {@link #requeueOrFail(Long, String, String)} with {@code expectedWorkerId=null}
     * (WOC-27) — behavior for these callers is unchanged.
     */
    public void requeueOrFail(Long jobId, String reason) {
        requeueOrFail(jobId, reason, null);
    }

    /**
     * Requeues or fails the given job. Idempotent: if it already left {@code RUNNING} (e.g. a
     * concurrent result arrived, or a previous sweep already handled it), this is a silent no-op —
     * safe to call repeatedly or after a Gateway restart.
     *
     * @param expectedWorkerId WOC-27: when non-{@code null}, the caller's asserted ownership is
     *                         re-checked <em>inside</em> the job-row lock (not just an outer, unlocked
     *                         pre-check the caller may have already done) — a mismatch is a no-op
     *                         ({@link RequeueOutcome.Outcome#OWNERSHIP_MISMATCH}). {@code null} means "no
     *                         ownership expectation" (the sweep's own call sites, which have no
     *                         {@code workerId} of their own to assert).
     */
    public RequeueOutcome requeueOrFail(Long jobId, String reason, String expectedWorkerId) {
        RequeueOutcome outcome = requiresNewTransactionTemplate.execute(
                status -> requeueOrFailJobOnly(jobId, reason, expectedWorkerId));
        if (outcome == null) {
            outcome = RequeueOutcome.notFound();
        }
        if (outcome.reviewId() != null) {
            // CSR-17: the job-row lock above was released when that transaction committed; this call
            // takes its own, independent lock on the parent row only now.
            chunkCoordinator.recomputeAndApply(outcome.reviewId());
        }
        return outcome;
    }

    private RequeueOutcome requeueOrFailJobOnly(Long jobId, String reason, String expectedWorkerId) {
        applyLockTimeout();
        ReviewJob job = reviewJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null) {
            log.warn("requeueOrFail called for missing jobId={}", jobId);
            return RequeueOutcome.notFound();
        }
        if (job.getStatus() != JobStatus.RUNNING) {
            log.debug("Job {} no longer RUNNING ({}), skipping retry/fail sweep", jobId, job.getStatus());
            return RequeueOutcome.noopNotRunning();
        }
        // WOC-27: the ownership check MUST be re-evaluated inside the locked transaction, not only in an
        // outer unlocked pre-check -- otherwise a sweep-requeue/re-claim race could apply this call to a
        // *different*, healthy attempt belonging to a different worker.
        if (expectedWorkerId != null && !expectedWorkerId.equals(job.getWorkerId())) {
            log.warn("requeueOrFail ownership mismatch: jobId={} claimedBy={} callerWorkerId={}",
                    jobId, job.getWorkerId(), expectedWorkerId);
            return RequeueOutcome.ownershipMismatch();
        }

        String workerId = job.getWorkerId();
        Long backendId = job.getBackendId();
        int maxAttempts = properties.getRetry().getMaxAttempts();

        if (job.getAttempts() >= maxAttempts) {
            String suffix = " (attempts exhausted: " + job.getAttempts() + "/" + maxAttempts + ")";
            job.setLastError(sanitizeLastError(reason + suffix));
            jobStateMachine.transition(job, JobStatus.FAILED, EventType.FAILED, workerId, backendId, reason + suffix);
            log.info("Job {} (reviewId={}, chunkIndex={}) FAILED: {} (attempts {}/{})",
                    job.getId(), job.getReviewId(), job.getChunkIndex(), reason, job.getAttempts(), maxAttempts);
            reviewJobRepository.save(job);
            return RequeueOutcome.failed(job.getReviewId());
        }

        String suffix = " (attempt " + job.getAttempts() + "/" + maxAttempts + ")";
        job.setLastError(sanitizeLastError(reason + suffix));
        job.setNotBefore(computeNotBefore());
        jobStateMachine.transition(job, JobStatus.QUEUED, EventType.RETRY, workerId, backendId, reason + suffix);
        log.info("Job {} (reviewId={}, chunkIndex={}) requeued for retry: {} (attempt {}/{})",
                job.getId(), job.getReviewId(), job.getChunkIndex(), reason, job.getAttempts(), maxAttempts);
        reviewJobRepository.save(job);
        return RequeueOutcome.requeued(job.getReviewId());
    }

    private String sanitizeLastError(String raw) {
        return textSanitizer.sanitizeSingleLine(raw, MAX_LAST_ERROR_LENGTH);
    }

    /**
     * WOC-40/WOR-14: {@code null} when {@code gateway.retry.requeue-delay} is {@code 0} (the documented
     * escape hatch — immediate-claim behavior, unchanged from pre-branch). Otherwise computed against
     * the <b>database</b> clock, never the JVM's (WOT-08: this deployment's own suspected container clock
     * skew is exactly the failure mode a two-clock computation would reintroduce).
     */
    private Instant computeNotBefore() {
        Duration delay = properties.getRetry().getRequeueDelay();
        if (delay == null || delay.isZero()) {
            return null;
        }
        return databaseNow().plus(delay);
    }

    private Instant databaseNow() {
        Object result = entityManager.createNativeQuery("SELECT now()").getSingleResult();
        if (result instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (result instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (result instanceof Instant instant) {
            return instant;
        }
        if (result instanceof java.time.LocalDateTime ldt) {
            return ldt.toInstant(ZoneOffset.UTC);
        }
        throw new IllegalStateException("Unexpected SELECT now() result type: "
                + (result == null ? "null" : result.getClass()));
    }

    /**
     * CSR-17: bounds how long this transaction can wait for the job-row lock, so a lock wait can never
     * pin a Hikari connection indefinitely (pool size is 20).
     */
    private void applyLockTimeout() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
    }
}

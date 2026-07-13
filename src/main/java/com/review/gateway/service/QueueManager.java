package com.review.gateway.service;

import com.review.gateway.exception.JobNotClaimableException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.ClaimedJob;
import com.review.gateway.service.dto.HeartbeatResult;
import com.review.gateway.service.dto.ResultOutcome;
import com.review.gateway.service.dto.SubmitResultCommand;
import com.review.gateway.service.dto.SubmitResultOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Implements the Worker-facing queue operations (architecture §5, §6, SR-04).
 */
@Service
public class QueueManager {

    private static final Logger log = LoggerFactory.getLogger(QueueManager.class);

    private final ReviewRepository reviewRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final ReviewInputRepository reviewInputRepository;
    private final BackendDispatcher backendDispatcher;
    private final StateMachine stateMachine;
    private final EventService eventService;
    private final ResultProcessor resultProcessor;

    public QueueManager(ReviewRepository reviewRepository,
                         ReviewJobRepository reviewJobRepository,
                         ReviewInputRepository reviewInputRepository,
                         BackendDispatcher backendDispatcher,
                         StateMachine stateMachine,
                         EventService eventService,
                         ResultProcessor resultProcessor) {
        this.reviewRepository = reviewRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.reviewInputRepository = reviewInputRepository;
        this.backendDispatcher = backendDispatcher;
        this.stateMachine = stateMachine;
        this.eventService = eventService;
        this.resultProcessor = resultProcessor;
    }

    /**
     * Claims the next queued Review for {@code backendName}, in one short transaction (architecture
     * §5): capacity check, {@code FOR UPDATE SKIP LOCKED} select, then transition + upsert. The row
     * lock is released the instant this transaction commits; ownership afterward is enforced by
     * {@code RUNNING} status + heartbeat, never a held DB lock.
     *
     * @return the claimed job's payload, or empty if there is nothing to claim right now (204) —
     *         which also covers "backend not ACTIVE" and "backend at capacity"
     *         ({@link JobNotClaimableException} is caught here, not propagated, for those two cases).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<ClaimedJob> claim(String backendName, String workerId) {
        Backend backend;
        try {
            backend = backendDispatcher.resolveClaimableBackend(backendName);
        } catch (JobNotClaimableException notClaimable) {
            log.debug("Claim declined for backend '{}': {}", backendName, notClaimable.getMessage());
            return Optional.empty();
        }

        Optional<Long> reviewIdOpt = reviewRepository.findNextQueuedReviewIdForUpdate();
        if (reviewIdOpt.isEmpty()) {
            return Optional.empty();
        }
        Long reviewId = reviewIdOpt.get();

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalStateException("Claimed review " + reviewId + " vanished within its own transaction"));

        review.incrementAttempts();
        stateMachine.transition(review, ReviewStatus.RUNNING, EventType.CLAIMED, workerId, backend.getId(),
                "attempt=" + review.getAttempts());
        // Explicit save (belt-and-suspenders): the surrounding @Transactional boundary would flush this
        // via dirty-checking at commit regardless, but making it explicit keeps claim()'s persistence
        // effects self-contained and independent of exactly when/whether a flush happens to be triggered.
        reviewRepository.save(review);
        eventService.record(reviewId, EventType.RUNNING, workerId, backend.getId(), "execution started");

        ReviewJob job = upsertJob(reviewId, backend.getId(), workerId);

        ReviewInput input = reviewInputRepository.findByReviewId(reviewId)
                .orElseThrow(() -> new IllegalStateException("Review " + reviewId + " has no review_inputs row"));

        log.info("Job claimed: reviewId={} jobId={} backend={} workerId={}", reviewId, job.getId(), backendName, workerId);
        return Optional.of(new ClaimedJob(job.getId(), reviewId, input.getDiff(), input.getPromptVersion()));
    }

    private ReviewJob upsertJob(Long reviewId, Long backendId, String workerId) {
        Instant now = Instant.now();
        ReviewJob job = reviewJobRepository.findByReviewId(reviewId).orElseGet(() -> new ReviewJob(reviewId, backendId, workerId));
        job.setBackendId(backendId);
        job.setWorkerId(workerId);
        job.setHeartbeatAt(now);
        job.setClaimedAt(now);
        job.setStartedAt(now);
        job.setFinishedAt(null);
        job.setLastError(null);
        return reviewJobRepository.save(job);
    }

    /**
     * Handles {@code POST /jobs/{id}/heartbeat} (req. 1.7, SR-04). Ownership (the caller must be the
     * worker that currently holds this job) is checked before anything is mutated; a mismatch changes
     * nothing and is reported distinctly from "not found" so a future controller can return 403/404
     * accordingly. {@code shouldContinue} is {@code false} whenever the Review is no longer
     * {@code RUNNING} (covers OBSOLETE/CANCELLED explicitly, and is a safe default for any other
     * reason it left RUNNING).
     */
    @Transactional
    public HeartbeatResult heartbeat(Long jobId, String workerId) {
        Optional<ReviewJob> jobOpt = reviewJobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.debug("Heartbeat for unknown jobId={}", jobId);
            return HeartbeatResult.notFound();
        }
        ReviewJob job = jobOpt.get();

        if (!isOwner(job, workerId)) {
            log.warn("Heartbeat ownership mismatch: jobId={} claimedBy={} callerWorkerId={}", jobId, job.getWorkerId(), workerId);
            return HeartbeatResult.ownershipMismatch();
        }

        Review review = reviewRepository.findById(job.getReviewId())
                .orElseThrow(() -> new IllegalStateException("Job " + jobId + " references missing review " + job.getReviewId()));

        if (review.getStatus() != ReviewStatus.RUNNING) {
            log.debug("Heartbeat for jobId={} but review {} is {} (not RUNNING) -> shouldContinue=false",
                    jobId, review.getId(), review.getStatus());
            return HeartbeatResult.accepted(false);
        }

        job.setHeartbeatAt(Instant.now());
        reviewJobRepository.save(job);
        eventService.record(review.getId(), EventType.HEARTBEAT, workerId, job.getBackendId(), null);
        return HeartbeatResult.accepted(true);
    }

    /**
     * Handles {@code POST /jobs/{id}/result} (req. 1.9, SR-04, SR-21). Idempotent: if the Review is
     * no longer {@code RUNNING} the submission is acknowledged without changing any state. Ownership
     * mismatch mutates nothing. Otherwise delegates to {@link ResultProcessor}, which stores the raw
     * response before attempting to parse it.
     */
    @Transactional
    public SubmitResultOutcome submitResult(Long jobId, String workerId, SubmitResultCommand command) {
        Optional<ReviewJob> jobOpt = reviewJobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.debug("Result submission for unknown jobId={}", jobId);
            return SubmitResultOutcome.notFound();
        }
        ReviewJob job = jobOpt.get();

        Review review = reviewRepository.findById(job.getReviewId())
                .orElseThrow(() -> new IllegalStateException("Job " + jobId + " references missing review " + job.getReviewId()));

        if (!isOwner(job, workerId)) {
            log.warn("Result submission ownership mismatch: jobId={} claimedBy={} callerWorkerId={}", jobId, job.getWorkerId(), workerId);
            return SubmitResultOutcome.ownershipMismatch(review.getStatus());
        }

        if (review.getStatus() != ReviewStatus.RUNNING) {
            log.info("Idempotent no-op result submission: jobId={} reviewId={} currentStatus={}",
                    jobId, review.getId(), review.getStatus());
            return SubmitResultOutcome.idempotentNoop(review.getStatus());
        }

        ReviewStatus finalStatus = resultProcessor.process(review.getId(), job.getId(), workerId, job.getBackendId(), command);
        return SubmitResultOutcome.accepted(finalStatus);
    }

    private boolean isOwner(ReviewJob job, String workerId) {
        return workerId != null && workerId.equals(job.getWorkerId());
    }
}

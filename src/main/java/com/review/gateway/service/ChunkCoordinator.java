package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewChunk;
import com.review.gateway.model.ReviewComment;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.ParsedComment;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Derives {@code reviews.status} from the set of a review's {@link ReviewJob} statuses and applies it
 * through the existing {@link StateMachine} (V2, diff chunking; supersedes the architect's original
 * lock-ordering draft — CSR-17, see class-level notes on {@code QueueManager}/{@code RetryManager} for
 * the full rationale).
 *
 * <p><b>CSR-17 (lock ordering):</b> every method here locks the <em>parent</em> {@code reviews} row
 * first (via {@link ReviewRepository#findByIdForUpdate}), in its own {@code REQUIRES_NEW} transaction,
 * and is always called <em>after</em> whatever child-job transaction triggered it has already
 * committed (releasing the job-row lock) — never nested inside a job-row lock. This is the "parent →
 * child, independently" direction the fix requires: no code path here waits on a job-row lock while
 * still holding the parent lock in reverse of some other path's ordering, because nothing here is ever
 * invoked while a job-row lock from the same logical operation is still held.
 *
 * <p>Derivation rule (applied only while the Review is not yet terminal/PUBLISHED):
 * <pre>
 * any child FAILED           -&gt; FAILED
 * all children COMPLETED     -&gt; COMPLETED
 * any child RUNNING          -&gt; RUNNING
 * any child COMPLETED (rest QUEUED) -&gt; RUNNING
 * all children QUEUED        -&gt; QUEUED
 * </pre>
 * For {@code chunkCount == 1} this collapses exactly onto pre-V2 behavior (one job, one status).
 */
@Service
public class ChunkCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ChunkCoordinator.class);

    private static final Set<ReviewStatus> TERMINAL_OR_PUBLISHED = EnumSet.of(
            ReviewStatus.PUBLISHED, ReviewStatus.FAILED, ReviewStatus.CANCELLED, ReviewStatus.OBSOLETE);

    private final ReviewRepository reviewRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final ReviewChunkRepository reviewChunkRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final StateMachine stateMachine;
    private final JobStateMachine jobStateMachine;
    private final GatewayProperties properties;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ChunkCoordinator(ReviewRepository reviewRepository,
                             ReviewJobRepository reviewJobRepository,
                             ReviewChunkRepository reviewChunkRepository,
                             ReviewCommentRepository reviewCommentRepository,
                             StateMachine stateMachine,
                             JobStateMachine jobStateMachine,
                             GatewayProperties properties,
                             EntityManager entityManager,
                             PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.reviewChunkRepository = reviewChunkRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.stateMachine = stateMachine;
        this.jobStateMachine = jobStateMachine;
        this.properties = properties;
        this.entityManager = entityManager;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        this.requiresNewTransactionTemplate.setName("ChunkCoordinator");
    }

    /**
     * Re-derives and applies the parent status from its children's current statuses. Called after any
     * child-job status change whose effect on the parent doesn't also require persisting comments
     * (claim -&gt; RUNNING, retry -&gt; QUEUED, parse-failure -&gt; FAILED).
     *
     * <p>Deliberately uses {@link TransactionTemplate} rather than a proxied {@code @Transactional}
     * annotation: like {@code ResultProcessor}/{@code ReviewService} elsewhere in this codebase, this
     * class's methods are always meant to be a genuinely separate, independently-committed transaction
     * regardless of how the caller invoked it (including in tests that construct this class directly,
     * bypassing Spring AOP entirely).
     */
    public ReviewStatus recomputeAndApply(Long reviewId) {
        return requiresNewTransactionTemplate.execute(status -> {
            applyLockTimeout();
            Review review = reviewRepository.findByIdForUpdate(reviewId).orElse(null);
            if (review == null) {
                log.warn("recomputeAndApply called for missing reviewId={}", reviewId);
                return null;
            }
            return recomputeAndApplyLocked(review);
        });
    }

    /**
     * Same as {@link #recomputeAndApply}, but additionally persists a completed chunk's parsed
     * comments first, under the same parent-review lock and in the same transaction — this is what
     * CSR-21 requires: the review-level comment-count cap must be enforced by a single count-then-
     * insert step guarded by one lock, not by each chunk racing an independent per-chunk cap.
     */
    public ReviewStatus completeChunkAndRecompute(Long reviewId, Integer chunkIndex, List<ParsedComment> parsedComments) {
        return requiresNewTransactionTemplate.execute(status -> {
            applyLockTimeout();
            Review review = reviewRepository.findByIdForUpdate(reviewId).orElse(null);
            if (review == null) {
                log.warn("completeChunkAndRecompute called for missing reviewId={}", reviewId);
                return null;
            }
            persistCappedComments(review, chunkIndex, parsedComments);
            return recomputeAndApplyLocked(review);
        });
    }

    private ReviewStatus recomputeAndApplyLocked(Review review) {
        if (TERMINAL_OR_PUBLISHED.contains(review.getStatus())) {
            return review.getStatus();
        }
        List<ReviewJob> jobs = reviewJobRepository.findByReviewIdOrderByChunkIndexAsc(review.getId());
        if (jobs.isEmpty()) {
            return review.getStatus();
        }
        ReviewStatus target = derive(jobs);
        if (target == null || target == review.getStatus()) {
            return review.getStatus();
        }
        if (!stateMachine.isLegal(review.getStatus(), target)) {
            log.debug("ChunkCoordinator: derived target {} is not legal from {} for reviewId={}, skipping",
                    target, review.getStatus(), review.getId());
            return review.getStatus();
        }
        stateMachine.transition(review, target, eventTypeFor(target), summarize(jobs));
        reviewRepository.save(review);

        if (target == ReviewStatus.FAILED) {
            cascadeCancelSiblings(review, jobs);
        }
        return target;
    }

    /** @return the derived target status, or {@code null} if no rule matched (caller treats as "no change"). */
    private ReviewStatus derive(List<ReviewJob> jobs) {
        if (jobs.stream().anyMatch(j -> j.getStatus() == JobStatus.FAILED)) {
            return ReviewStatus.FAILED;
        }
        if (jobs.stream().allMatch(j -> j.getStatus() == JobStatus.COMPLETED)) {
            return ReviewStatus.COMPLETED;
        }
        if (jobs.stream().anyMatch(j -> j.getStatus() == JobStatus.RUNNING)) {
            return ReviewStatus.RUNNING;
        }
        if (jobs.stream().anyMatch(j -> j.getStatus() == JobStatus.COMPLETED)) {
            return ReviewStatus.RUNNING; // some completed, rest still queued
        }
        if (jobs.stream().allMatch(j -> j.getStatus() == JobStatus.QUEUED)) {
            return ReviewStatus.QUEUED;
        }
        // Defensive fallback for an all-CANCELLED/all-OBSOLETE child set: normally reached directly via
        // ReviewService.cancel/sweepObsolete (parent-first), not through this derivation path.
        if (jobs.stream().allMatch(j -> j.getStatus() == JobStatus.CANCELLED)) {
            return ReviewStatus.CANCELLED;
        }
        if (jobs.stream().allMatch(j -> j.getStatus() == JobStatus.OBSOLETE)) {
            return ReviewStatus.OBSOLETE;
        }
        return null;
    }

    private EventType eventTypeFor(ReviewStatus target) {
        return switch (target) {
            case COMPLETED -> EventType.COMPLETED;
            case FAILED -> EventType.FAILED;
            case QUEUED -> EventType.RETRY;
            case CANCELLED -> EventType.CANCELLED;
            case OBSOLETE -> EventType.OBSOLETE;
            default -> EventType.RUNNING;
        };
    }

    private String summarize(List<ReviewJob> jobs) {
        long completed = jobs.stream().filter(j -> j.getStatus() == JobStatus.COMPLETED).count();
        long failed = jobs.stream().filter(j -> j.getStatus() == JobStatus.FAILED).count();
        long running = jobs.stream().filter(j -> j.getStatus() == JobStatus.RUNNING).count();
        return "chunks=" + jobs.size() + " completed=" + completed + " running=" + running + " failed=" + failed;
    }

    /**
     * Sibling cancellation on chunk-job permanent failure (parent-then-child propagation, CSR-17): the
     * parent lock is already held by the caller. Running siblings learn via their next heartbeat
     * ({@code shouldContinue=false}) within {@code gateway.heartbeat.timeout}, at which point the
     * Worker's {@code AbortSignal} tears down its in-flight llama-server call.
     */
    private void cascadeCancelSiblings(Review review, List<ReviewJob> jobs) {
        Integer causingChunkIndex = jobs.stream()
                .filter(j -> j.getStatus() == JobStatus.FAILED)
                .map(ReviewJob::getChunkIndex)
                .findFirst()
                .orElse(null);
        for (ReviewJob sibling : jobs) {
            if (sibling.getStatus() == JobStatus.QUEUED || sibling.getStatus() == JobStatus.RUNNING) {
                jobStateMachine.transition(sibling, JobStatus.CANCELLED, EventType.CANCELLED,
                        sibling.getWorkerId(), sibling.getBackendId(),
                        "cancelled: sibling chunk " + causingChunkIndex + " failed permanently");
                reviewJobRepository.save(sibling);
                log.info("Job {} (reviewId={}, chunkIndex={}) cancelled: sibling chunk {} failed permanently",
                        sibling.getId(), review.getId(), sibling.getChunkIndex(), causingChunkIndex);
            }
        }
    }

    /**
     * CSR-21: counts existing {@code review_comments} for the review and inserts only up to the
     * remaining budget, under the parent-review lock already held by the caller — this is what makes
     * the review-level cap race-free across concurrently-completing chunks. Exact-match dedup (CSR-06
     * spirit) is applied only when the review has more than one chunk (the rare hunk-split fallback,
     * §2, is the only case where the same file/line can appear in two chunks).
     */
    private void persistCappedComments(Review review, Integer chunkIndex, List<ParsedComment> parsedComments) {
        if (parsedComments == null || parsedComments.isEmpty()) {
            return;
        }
        int maxTotal = Math.max(0, properties.getPublish().getMaxCommentCount());
        long existingCount = reviewCommentRepository.countByReviewId(review.getId());
        long remaining = Math.max(0, maxTotal - existingCount);
        if (remaining <= 0) {
            log.warn("reviewId={} already at the comment cap ({}); dropping {} comment(s) from chunk {}",
                    review.getId(), maxTotal, parsedComments.size(), chunkIndex);
            return;
        }

        boolean multiChunk = isMultiChunk(review.getId());
        List<ReviewComment> existingForDedup = multiChunk
                ? new ArrayList<>(reviewCommentRepository.findByReviewId(review.getId()))
                : List.of();

        int inserted = 0;
        int droppedForCap = 0;
        int droppedForDup = 0;
        for (ParsedComment candidate : parsedComments) {
            if (inserted >= remaining) {
                droppedForCap++;
                continue;
            }
            if (multiChunk && isDuplicate(existingForDedup, candidate)) {
                droppedForDup++;
                continue;
            }
            ReviewComment saved = reviewCommentRepository.save(new ReviewComment(
                    review.getId(), chunkIndex, candidate.filePath(), candidate.lineNumber(),
                    candidate.severity(), candidate.text()));
            if (multiChunk) {
                existingForDedup.add(saved);
            }
            inserted++;
        }
        if (droppedForCap > 0) {
            log.warn("reviewId={} chunk={}: dropped {} comment(s) beyond the review-level cap of {}",
                    review.getId(), chunkIndex, droppedForCap, maxTotal);
        }
        if (droppedForDup > 0) {
            log.debug("reviewId={} chunk={}: dropped {} exact-duplicate comment(s) (cross-chunk hunk-split overlap)",
                    review.getId(), chunkIndex, droppedForDup);
        }
    }

    private boolean isMultiChunk(Long reviewId) {
        List<ReviewChunk> chunks = reviewChunkRepository.findByReviewIdOrderByChunkIndexAsc(reviewId);
        if (chunks.isEmpty()) {
            return false;
        }
        Integer chunkCount = chunks.get(0).getChunkCount();
        return (chunkCount != null && chunkCount > 1) || chunks.size() > 1;
    }

    private boolean isDuplicate(List<ReviewComment> existing, ParsedComment candidate) {
        for (ReviewComment comment : existing) {
            if (Objects.equals(comment.getFilePath(), candidate.filePath())
                    && Objects.equals(comment.getLineNumber(), candidate.lineNumber())
                    && Objects.equals(comment.getComment(), candidate.text())) {
                return true;
            }
        }
        return false;
    }

    /**
     * CSR-17: bounds how long a transaction here can wait for the parent-review row lock, so a lock
     * wait can never pin a Hikari connection indefinitely (pool size is 20). A timed-out wait surfaces
     * as a {@code QueryTimeoutException}/{@code PessimisticLockingFailureException}, mapped by
     * {@code GlobalExceptionHandler} to a clean 409, never a raw 500.
     */
    private void applyLockTimeout() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
    }
}

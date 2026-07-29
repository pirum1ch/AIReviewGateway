package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.ReviewResult;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.repository.ReviewResultRepository;
import com.review.gateway.service.dto.ParsedComment;
import com.review.gateway.service.dto.SubmitResultCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Handles the "job finished, now what" half of req. 1.9 (V2, diff chunking: this now operates at the
 * per-chunk JOB level, not the Review level). Phase 1 (this class) locks only the {@code review_jobs}
 * row for this chunk, stores the raw response, parses it, and transitions the JOB to
 * {@code COMPLETED}/{@code FAILED} — all in one committed transaction. Phase 2 ({@link
 * ChunkCoordinator}) is a strictly separate, independent transaction that locks the <em>parent</em>
 * {@code reviews} row, persists any parsed comments under the review-level cap (CSR-21), and re-derives
 * the parent's status. This two-phase split is CSR-17's lock-ordering fix: phase 1's job-row lock is
 * always released (transaction committed) before phase 2 ever asks for the parent-row lock, so no
 * transaction here ever holds a job lock while waiting on the parent lock.
 */
@Service
public class ResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(ResultProcessor.class);

    private final ReviewRepository reviewRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final ReviewResultRepository reviewResultRepository;
    private final CommentParser commentParser;
    private final JobStateMachine jobStateMachine;
    private final ChunkCoordinator chunkCoordinator;
    private final GatewayProperties properties;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ResultProcessor(ReviewRepository reviewRepository,
                            ReviewJobRepository reviewJobRepository,
                            ReviewResultRepository reviewResultRepository,
                            CommentParser commentParser,
                            JobStateMachine jobStateMachine,
                            ChunkCoordinator chunkCoordinator,
                            GatewayProperties properties,
                            PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.reviewResultRepository = reviewResultRepository;
        this.commentParser = commentParser;
        this.jobStateMachine = jobStateMachine;
        this.chunkCoordinator = chunkCoordinator;
        this.properties = properties;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("ResultProcessor");
    }

    /**
     * @return the Review's status after this chunk's contribution has been applied (may still be
     *         {@code RUNNING} if sibling chunks are not yet done)
     */
    public ReviewStatus process(Long reviewId, Long jobId, String workerId, Long backendId, SubmitResultCommand command) {
        // F02-01/SR-21: cap the raw response BEFORE it is persisted and BEFORE it is handed to
        // CommentParser (which otherwise runs indexOf/lastIndexOf/substring + a full JSON parse over
        // the whole blob) -- an oversized response (compromised worker, or a prompt-injected model,
        // T-19) would otherwise cause unbounded storage growth and CPU/heap pressure on the single
        // Gateway (SPOF).
        CappedRawResponse capped = capRawResponseIfNeeded(command.rawResponse());
        SubmitResultCommand effectiveCommand = capped.truncated()
                ? new SubmitResultCommand(capped.value(), command.promptTokens(), command.completionTokens(),
                        command.durationMs(), command.model())
                : command;

        JobPhaseOutcome outcome = requiresNewTransactionTemplate.execute(status ->
                processJobPhase(jobId, workerId, backendId, effectiveCommand, capped));

        if (outcome == null) {
            // Job already left RUNNING concurrently (or vanished). Idempotent no-op for THIS job, but
            // still worth attempting the parent recompute: the transaction that actually won the race
            // may not have reached its own phase 2 yet, and recomputeAndApply is itself lock-guarded and
            // safe to call redundantly (a second call for an already-applied target is a no-op).
            ReviewStatus result = chunkCoordinator.recomputeAndApply(reviewId);
            return result != null ? result : currentReviewStatus(reviewId);
        }
        if (outcome.parsedComments() != null) {
            ReviewStatus result = chunkCoordinator.completeChunkAndRecompute(reviewId, outcome.chunkIndex(), outcome.parsedComments());
            return result != null ? result : currentReviewStatus(reviewId);
        }
        // Parse failed -> the job already transitioned to FAILED in phase 1; just recompute/cascade.
        ReviewStatus result = chunkCoordinator.recomputeAndApply(reviewId);
        return result != null ? result : currentReviewStatus(reviewId);
    }

    private ReviewStatus currentReviewStatus(Long reviewId) {
        return reviewRepository.findById(reviewId).map(Review::getStatus).orElse(ReviewStatus.FAILED);
    }

    /**
     * Phase 1: locks the job row (CSR-17 — never the parent), stores the raw response idempotently
     * per {@code (review_id, chunk_index)}, parses it, and transitions the job to
     * {@code COMPLETED}/{@code FAILED}. Runs inside {@link #requiresNewTransactionTemplate}.
     *
     * @return {@code null} if the job was no longer {@code RUNNING} (idempotent no-op)
     */
    private JobPhaseOutcome processJobPhase(Long jobId, String workerId, Long backendId,
                                             SubmitResultCommand command, CappedRawResponse capped) {
        ReviewJob job = reviewJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || job.getStatus() != JobStatus.RUNNING) {
            log.debug("Job {} no longer RUNNING when processing result, skipping", jobId);
            return null;
        }
        Long reviewId = job.getReviewId();
        Integer chunkIndex = job.getChunkIndex();

        if (!reviewResultRepository.existsByReviewIdAndChunkIndex(reviewId, chunkIndex)) {
            storeRawResult(reviewId, chunkIndex, jobId, backendId, command);
        } else {
            log.debug("review_results already present for reviewId={} chunkIndex={}, skipping insert", reviewId, chunkIndex);
        }

        List<ParsedComment> parsed;
        try {
            parsed = commentParser.parse(command.rawResponse(), fairShareCommentCap(reviewId));
        } catch (RuntimeException parseError) {
            // F02-03/SR-14: log only the exception class, never parseError.toString().
            log.warn("Comment parsing failed for reviewId={} chunkIndex={}: {}",
                    reviewId, chunkIndex, parseError.getClass().getSimpleName());
            jobStateMachine.transition(job, JobStatus.FAILED, EventType.FAILED, workerId, backendId,
                    "parse error: " + parseError.getClass().getSimpleName() + capped.auditNote());
            finishJob(job);
            reviewJobRepository.save(job);
            return new JobPhaseOutcome(chunkIndex, null);
        }

        jobStateMachine.transition(job, JobStatus.COMPLETED, EventType.COMPLETED, workerId, backendId,
                "parsed=" + parsed.size() + capped.auditNote());
        finishJob(job);
        reviewJobRepository.save(job);
        return new JobPhaseOutcome(chunkIndex, parsed);
    }

    /** {@code max(1, floor(maxCommentCount / chunkCount))} — see {@link CommentParser#parse(String, int)}. */
    private int fairShareCommentCap(Long reviewId) {
        int maxTotal = Math.max(0, properties.getPublish().getMaxCommentCount());
        int chunkCount = Math.max(1, reviewJobRepository.findByReviewIdOrderByChunkIndexAsc(reviewId).size());
        return Math.max(1, maxTotal / chunkCount);
    }

    /**
     * F02-01/SR-21: truncates {@code rawResponse} to {@code gateway.publish.max-raw-response-length}
     * if it exceeds the cap, appending a clearly-identifiable marker. Never rejects.
     */
    private CappedRawResponse capRawResponseIfNeeded(String rawResponse) {
        int max = Math.max(0, properties.getPublish().getMaxRawResponseLength());
        int originalLength = rawResponse == null ? 0 : rawResponse.length();
        if (rawResponse == null || originalLength <= max) {
            return new CappedRawResponse(rawResponse, false, originalLength, max);
        }
        String suffix = "...[TRUNCATED by Gateway: raw response exceeded configured limit]";
        int cut = Math.max(0, max - suffix.length());
        String truncated = rawResponse.substring(0, cut) + suffix;
        log.warn("Raw response for reviewId processing exceeded the configured cap ({} > {} chars); truncating (SR-21)",
                originalLength, max);
        return new CappedRawResponse(truncated, true, originalLength, max);
    }

    private void storeRawResult(Long reviewId, Integer chunkIndex, Long jobId, Long backendId, SubmitResultCommand command) {
        Integer totalTokens = (command.promptTokens() != null && command.completionTokens() != null)
                ? command.promptTokens() + command.completionTokens()
                : null;
        ReviewResult result = new ReviewResult(reviewId, chunkIndex, jobId, command.rawResponse(), null,
                command.promptTokens(), command.completionTokens(), totalTokens,
                command.durationMs(), command.model(), backendId);
        reviewResultRepository.save(result);
    }

    /**
     * F02-01/SR-21: carries whether {@link #capRawResponseIfNeeded} truncated the raw response, so the
     * fact (never the content — SR-14) can be recorded in the audit trail.
     */
    private record CappedRawResponse(String value, boolean truncated, int originalLength, int limit) {
        String auditNote() {
            return truncated ? ("; raw_response truncated " + originalLength + "->" + limit + " chars") : "";
        }
    }

    /** Outcome of {@link #processJobPhase}: {@code parsedComments == null} means "parse failed, job FAILED". */
    private record JobPhaseOutcome(Integer chunkIndex, List<ParsedComment> parsedComments) {
    }

    private void finishJob(ReviewJob job) {
        job.setFinishedAt(Instant.now());
    }
}

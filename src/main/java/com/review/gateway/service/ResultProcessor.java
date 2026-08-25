package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewChunk;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.ReviewResult;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.FinishReason;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.OnInvalidResponse;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.repository.ReviewResultRepository;
import com.review.gateway.service.dto.ParsedComment;
import com.review.gateway.service.dto.SubmitResultCommand;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
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
 *
 * <p><b>Structured Review Output (SRO-35/36, non-negotiable lock ordering):</b> a structured-validation
 * failure is routed through {@link RetryManager#requeueOrFail}, which takes the <em>same</em> {@code
 * review_jobs} row lock phase 1 already held — so it MUST be called only after phase 1's transaction has
 * committed, from {@link #process} (this plain, non-{@code @Transactional} orchestrating method), never
 * from inside {@link #processJobPhase}. On a validation failure, {@code processJobPhase} leaves the job
 * {@code RUNNING} and returns a {@link JobPhaseOutcome} carrying a {@link ValidationFailure}; {@link
 * #process} then calls {@code retryManager.requeueOrFail}, which does its own CSR-17 parent recompute.
 * This mirrors {@code QueueManager.claimJobRow}/{@code failJobForMissingPromptSections} (PMR-09) and
 * {@code QueueManager.reportFailure} one-for-one.
 */
@Service
public class ResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(ResultProcessor.class);

    /**
     * SRO-38/68: prefixed onto every comment published by the {@code RETRY_THEN_FALLBACK} escape hatch —
     * a Gateway constant, first line, never derived from model output (WOR-04/SRO-41 discipline), so a
     * reviewer can never mistake a fallback comment for a structurally-guaranteed v3 comment.
     */
    private static final String UNVALIDATED_FALLBACK_PREFIX =
            "**UNVALIDATED** (structured-output fallback parse — per-file coverage is NOT guaranteed for this review)\n\n";

    private final ReviewRepository reviewRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final ReviewChunkRepository reviewChunkRepository;
    private final ReviewResultRepository reviewResultRepository;
    private final CommentParser commentParser;
    private final StructuredResponseParser structuredResponseParser;
    private final JobStateMachine jobStateMachine;
    private final ChunkCoordinator chunkCoordinator;
    private final RetryManager retryManager;
    private final MetricsCounters metricsCounters;
    private final GatewayProperties properties;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ResultProcessor(ReviewRepository reviewRepository,
                            ReviewJobRepository reviewJobRepository,
                            ReviewChunkRepository reviewChunkRepository,
                            ReviewResultRepository reviewResultRepository,
                            CommentParser commentParser,
                            StructuredResponseParser structuredResponseParser,
                            JobStateMachine jobStateMachine,
                            ChunkCoordinator chunkCoordinator,
                            RetryManager retryManager,
                            MetricsCounters metricsCounters,
                            GatewayProperties properties,
                            EntityManager entityManager,
                            PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.reviewChunkRepository = reviewChunkRepository;
        this.reviewResultRepository = reviewResultRepository;
        this.commentParser = commentParser;
        this.structuredResponseParser = structuredResponseParser;
        this.jobStateMachine = jobStateMachine;
        this.chunkCoordinator = chunkCoordinator;
        this.retryManager = retryManager;
        this.metricsCounters = metricsCounters;
        this.properties = properties;
        this.entityManager = entityManager;
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
                        command.durationMs(), command.model(), command.finishReason())
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
        if (outcome.validationFailure() != null) {
            // SRO-36: only now, after phase 1's transaction has already committed (releasing the job-row
            // lock), does RetryManager take that same lock again in its own REQUIRES_NEW transaction --
            // exactly the QueueManager.reportFailure precedent. requeueOrFail performs its own CSR-17
            // parent recompute internally, so nothing further is needed here beyond reading the result.
            retryManager.requeueOrFail(outcome.validationFailure().jobId(), outcome.validationFailure().reason(),
                    outcome.validationFailure().workerId());
            return currentReviewStatus(reviewId);
        }
        if (outcome.parsedComments() != null) {
            ReviewStatus result = chunkCoordinator.completeChunkAndRecompute(reviewId, outcome.chunkIndex(), outcome.parsedComments());
            return result != null ? result : currentReviewStatus(reviewId);
        }
        // Parse failed -> the job already transitioned to FAILED in phase 1; just recompute/cascade.
        ReviewStatus result = chunkCoordinator.recomputeAndApply(reviewId);
        return result != null ? result : currentReviewStatus(reviewId);
    }

    /**
     * QA round (structured-review-output): {@code process()} has no {@code @Transactional} of its own,
     * so when it is invoked from within an ambient caller transaction that has <em>already</em> loaded
     * this same {@code Review} row into its persistence context earlier in the same request (as
     * {@code QueueManager.submitResult} does, one line before calling {@link #process}) — a plain {@code
     * reviewRepository.findById(reviewId)} here returns Hibernate's already-managed, now-STALE instance
     * from the first-level cache rather than re-reading it, even though {@link RetryManager#requeueOrFail}
     * (validation-failure branch, SRO-36) or {@code ChunkCoordinator} committed a newer status moments
     * earlier in their own separate {@code REQUIRES_NEW} transaction. The DB itself is never wrong — only
     * this synchronous return value (and therefore the {@code POST /jobs/{id}/result} HTTP response body
     * a caller/Worker reads) was: a v3 validation failure that got requeued to {@code QUEUED} was reported
     * back as the job's pre-retry {@code RUNNING} status. Running this read in its own fresh {@code
     * REQUIRES_NEW} transaction (reusing the template already used for phase 1) gives it a brand-new
     * persistence context with no stale entry for this id, forcing a genuine re-read.
     */
    private ReviewStatus currentReviewStatus(Long reviewId) {
        ReviewStatus status = requiresNewTransactionTemplate.execute(txStatus ->
                reviewRepository.findById(reviewId).map(Review::getStatus).orElse(null));
        return status != null ? status : ReviewStatus.FAILED;
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
        applyLockTimeout();
        ReviewJob job = reviewJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (job == null || job.getStatus() != JobStatus.RUNNING) {
            log.debug("Job {} no longer RUNNING when processing result, skipping", jobId);
            return null;
        }
        Long reviewId = job.getReviewId();
        Integer chunkIndex = job.getChunkIndex();

        // SRO-37: the raw response is stored before parsing is even attempted, unconditionally, on the
        // first delivery only -- unchanged by this feature.
        if (!reviewResultRepository.existsByReviewIdAndChunkIndex(reviewId, chunkIndex)) {
            storeRawResult(reviewId, chunkIndex, jobId, backendId, command);
        } else {
            log.debug("review_results already present for reviewId={} chunkIndex={}, skipping insert", reviewId, chunkIndex);
        }

        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalStateException("Job " + jobId + " references missing review " + reviewId));
        // SRO-39: the kill switch means "v3 is parsed by the legacy CommentParser", not "v3 doesn't
        // exist" -- gateway.structured.enabled=false must behave exactly like v2 in an emergency.
        boolean structuredVersion = StructuredOutputSupport.isStructured(review.getPromptVersion())
                && properties.getStructured().isEnabled();

        if (structuredVersion) {
            return processStructuredJobPhase(job, workerId, backendId, command, capped, reviewId, chunkIndex);
        }
        return processLegacyJobPhase(job, workerId, backendId, command, capped, reviewId, chunkIndex);
    }

    /** v1/v2 (and v3 under the SRO-39 kill switch) — byte-for-byte the pre-existing behavior. */
    private JobPhaseOutcome processLegacyJobPhase(ReviewJob job, String workerId, Long backendId,
                                                    SubmitResultCommand command, CappedRawResponse capped,
                                                    Long reviewId, Integer chunkIndex) {
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
            return JobPhaseOutcome.parseFailed(chunkIndex);
        }

        jobStateMachine.transition(job, JobStatus.COMPLETED, EventType.COMPLETED, workerId, backendId,
                "parsed=" + parsed.size() + capped.auditNote());
        finishJob(job);
        reviewJobRepository.save(job);
        return JobPhaseOutcome.completed(chunkIndex, parsed);
    }

    /**
     * Structured (v3) Review with the decoder-constraint feature actually enabled. SRO-32/SRO-36: on a
     * validation failure, this method leaves the job {@code RUNNING} and returns a {@link
     * ValidationFailure} for {@link #process} to hand to {@link RetryManager} <em>after</em> this
     * transaction commits — it never calls {@code RetryManager} itself.
     */
    private JobPhaseOutcome processStructuredJobPhase(ReviewJob job, String workerId, Long backendId,
                                                        SubmitResultCommand command, CappedRawResponse capped,
                                                        Long reviewId, Integer chunkIndex) {
        ReviewChunk chunk = reviewChunkRepository.findByReviewIdAndChunkIndex(reviewId, chunkIndex)
                .orElseThrow(() -> new IllegalStateException(
                        "Job " + job.getId() + " references missing review_chunks row (reviewId=" + reviewId
                                + ", chunkIndex=" + chunkIndex + ")"));
        List<String> expectedPaths = parseFilePaths(chunk.getFilePaths());
        ReviewSchemaBuilder.SchemaOptions options = structuredSchemaOptions();

        StructuredResponseParser.ValidationResult result;
        try {
            result = structuredResponseParser.validate(command.rawResponse(), expectedPaths, capped.truncated(),
                    command.finishReason(), chunk.getDiff(), options);
        } catch (IllegalStateException invariantViolation) {
            // SRO-67c: an empty expected path set is OUR bug (SRO-67b should already have failed this
            // job closed at claim time) -- never retried, never reported as a validation kind.
            log.error("Structured validation invariant violation for reviewId={} chunkIndex={}: {}",
                    reviewId, chunkIndex, invariantViolation.getMessage());
            jobStateMachine.transition(job, JobStatus.FAILED, EventType.FAILED, workerId, backendId,
                    "structured-output: INVARIANT_VIOLATION");
            finishJob(job);
            reviewJobRepository.save(job);
            return JobPhaseOutcome.parseFailed(chunkIndex);
        }

        if (result.isSuccess()) {
            List<ParsedComment> comments = result.success().comments();
            jobStateMachine.transition(job, JobStatus.COMPLETED, EventType.COMPLETED, workerId, backendId,
                    "parsed=" + comments.size() + capped.auditNote());
            finishJob(job);
            reviewJobRepository.save(job);
            return JobPhaseOutcome.completed(chunkIndex, comments);
        }

        StructuredResponseParser.Failure failure = result.failure();
        metricsCounters.incrementStructuredValidationFailure(failure.kind().name());

        // SRO-38/68: the escape hatch is evaluated HERE, inside the same job-row lock, on the LAST
        // attempt only -- never as a FAILED->COMPLETED resurrection after RetryManager has already
        // committed the terminal state (which JobStateMachine would refuse as an illegal transition).
        boolean lastAttempt = job.getAttempts() >= Math.max(1, properties.getRetry().getMaxAttempts());
        boolean fallbackConfigured = onInvalidResponse() == OnInvalidResponse.RETRY_THEN_FALLBACK;
        if (lastAttempt && fallbackConfigured) {
            JobPhaseOutcome fallbackOutcome = tryStructuredFallback(job, workerId, backendId, command, capped, reviewId, chunkIndex);
            if (fallbackOutcome != null) {
                return fallbackOutcome;
            }
        }

        // Leave the job RUNNING -- process() calls RetryManager.requeueOrFail AFTER this transaction
        // commits (SRO-36).
        String reason = composeStructuredFailureReason(failure.kind(), failure.detail());
        return JobPhaseOutcome.validationFailure(chunkIndex, new ValidationFailure(job.getId(), workerId, reason));
    }

    /**
     * SRO-68: restricted to {@link CommentParser}'s genuine JSON-array branch only — never the
     * raw-transcript placeholder. Returns {@code null} (never a completed outcome) when the fallback
     * itself yields nothing, so the caller falls through to the normal {@code RETRY_THEN_FAIL} path.
     */
    private JobPhaseOutcome tryStructuredFallback(ReviewJob job, String workerId, Long backendId,
                                                    SubmitResultCommand command, CappedRawResponse capped,
                                                    Long reviewId, Integer chunkIndex) {
        List<ParsedComment> fallbackComments = commentParser.parseStructuredFallback(
                command.rawResponse(), fairShareCommentCap(reviewId));
        if (fallbackComments.isEmpty()) {
            log.warn("RETRY_THEN_FALLBACK configured but the legacy parser's genuine JSON-array branch "
                    + "also yielded nothing; failing exactly as RETRY_THEN_FAIL (reviewId={} chunkIndex={})",
                    reviewId, chunkIndex);
            return null;
        }
        List<ParsedComment> prefixed = withUnvalidatedPrefix(fallbackComments);
        log.warn("Structured validation failed on the final attempt; RETRY_THEN_FALLBACK published {} "
                + "unvalidated comment(s) instead of failing (reviewId={} chunkIndex={})", prefixed.size(), reviewId, chunkIndex);
        metricsCounters.incrementStructuredFallbackUsed();
        jobStateMachine.transition(job, JobStatus.COMPLETED, EventType.COMPLETED, workerId, backendId,
                "structured-output fallback: parsed=" + prefixed.size() + capped.auditNote());
        finishJob(job);
        reviewJobRepository.save(job);
        return JobPhaseOutcome.completed(chunkIndex, prefixed);
    }

    private List<ParsedComment> withUnvalidatedPrefix(List<ParsedComment> comments) {
        int maxLength = Math.max(0, properties.getPublish().getMaxCommentLength());
        List<ParsedComment> prefixed = new ArrayList<>();
        for (ParsedComment comment : comments) {
            String combined = UNVALIDATED_FALLBACK_PREFIX + comment.text();
            String capped = combined.length() > maxLength ? combined.substring(0, maxLength) : combined;
            prefixed.add(new ParsedComment(comment.filePath(), comment.lineNumber(), comment.severity(), capped));
        }
        return prefixed;
    }

    /** PMR-22-style defensive parse: never {@code Enum.valueOf} on the configured text (SRO-38). */
    private OnInvalidResponse onInvalidResponse() {
        return OnInvalidResponse.fromNullable(properties.getStructured().getOnInvalidResponse())
                .orElse(OnInvalidResponse.RETRY_THEN_FAIL);
    }

    /** SRO-40: no new {@code EventType} — the origin lives in this Gateway-constant prefix. */
    private String composeStructuredFailureReason(StructuredResponseParser.FailureKind kind, String detail) {
        String base = "structured-output: " + kind.name();
        return (detail == null || detail.isBlank()) ? base : base + "; " + detail;
    }

    private ReviewSchemaBuilder.SchemaOptions structuredSchemaOptions() {
        GatewayProperties.Structured cfg = properties.getStructured();
        return new ReviewSchemaBuilder.SchemaOptions(cfg.getMaxFindingsPerFile(), cfg.getMaxCommentChars(),
                cfg.getMaxSuggestionChars(), cfg.isPerFileSummary());
    }

    private List<String> parseFilePaths(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (Exception malformed) {
            log.warn("review_chunks.file_paths was not valid JSON (length={}); treating as an empty coverage list",
                    json.length());
            return List.of();
        }
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
                command.durationMs(), command.model(), backendId, normalizeFinishReason(command.finishReason()));
        reviewResultRepository.save(result);
    }

    /**
     * SRO-43/44: whitelist-parses the Worker-supplied {@code finish_reason} against {@link FinishReason}'s
     * closed vocabulary — never {@code Enum.valueOf} on the raw wire text. {@code null} (an old Worker, or
     * a backend/llama-server build that omits the field) is preserved as {@code null} rather than coerced
     * to {@code "unknown"} — the migration's own documented meaning of {@code NULL} for this column.
     */
    private String normalizeFinishReason(String rawFinishReason) {
        if (rawFinishReason == null) {
            return null;
        }
        FinishReason parsed = FinishReason.fromWireValue(rawFinishReason);
        if (parsed == FinishReason.UNKNOWN) {
            log.debug("Unrecognized finish_reason (length={}); storing as 'unknown'", rawFinishReason.length());
        }
        return parsed.wireValue();
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

    /**
     * Outcome of {@link #processJobPhase}. Exactly one of the three is meaningfully populated:
     * {@code parsedComments != null} — completed (legacy or structured, including the SRO-68 fallback);
     * {@code validationFailure != null} — a structured-validation failure, job left {@code RUNNING},
     * {@link RetryManager} must be called from {@link #process} after this transaction commits (SRO-36);
     * both {@code null} — a legacy parse-exception or an SRO-67c invariant violation, job already
     * transitioned to {@code FAILED} in phase 1.
     */
    private record JobPhaseOutcome(Integer chunkIndex, List<ParsedComment> parsedComments, ValidationFailure validationFailure) {

        static JobPhaseOutcome completed(Integer chunkIndex, List<ParsedComment> comments) {
            return new JobPhaseOutcome(chunkIndex, comments, null);
        }

        static JobPhaseOutcome parseFailed(Integer chunkIndex) {
            return new JobPhaseOutcome(chunkIndex, null, null);
        }

        static JobPhaseOutcome validationFailure(Integer chunkIndex, ValidationFailure validationFailure) {
            return new JobPhaseOutcome(chunkIndex, null, validationFailure);
        }
    }

    /** SRO-36: everything {@link #process} needs to call {@code RetryManager.requeueOrFail} after commit. */
    private record ValidationFailure(Long jobId, String workerId, String reason) {
    }

    private void finishJob(ReviewJob job) {
        job.setFinishedAt(Instant.now());
    }

    /**
     * F-DC-05: bounds how long this transaction can wait for the job-row lock, so a lock wait can never
     * pin a Hikari connection indefinitely (pool size 20). Previously missing here — this class had no
     * {@code EntityManager} injected at all — unlike the other {@code FOR UPDATE} sites
     * ({@code QueueManager}, {@code RetryManager}, {@code ChunkCoordinator}, {@code ReviewService}).
     */
    private void applyLockTimeout() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
    }
}

package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.PromptSectionsMissingException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewChunk;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.ReviewPromptSection;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.model.enums.StructuredOutputMode;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewPromptSectionRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.model.enums.JobFailureReason;
import com.review.gateway.service.dto.ClaimedJob;
import com.review.gateway.service.dto.FailureReportOutcome;
import com.review.gateway.service.dto.HeartbeatResult;
import com.review.gateway.service.dto.RequeueOutcome;
import com.review.gateway.service.dto.SubmitResultCommand;
import com.review.gateway.service.dto.SubmitResultOutcome;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Implements the Worker-facing queue operations (architecture §5, §6, SR-04; V2, diff chunking).
 *
 * <p><b>CSR-17 (lock ordering — supersedes the architect's original draft):</b> {@link #claim} no
 * longer locks the parent {@code reviews} row at all — only the {@code review_jobs} row being claimed
 * ({@link #claimJobRow}, its own {@code REQUIRES_NEW} transaction). Once that transaction has committed
 * (releasing the job-row lock), a separate, independent transaction ({@link ChunkCoordinator}) locks
 * the parent row to re-derive its status. The "doomed job" case — the parent went
 * CANCELLED/OBSOLETE while a claim was in flight — is handled lock-free afterward: a plain
 * (unlocked) read of the parent's current status is a best-effort courtesy check, not a correctness
 * mechanism; correctness comes from the fact that a genuinely in-flight claim always eventually sees
 * the parent's cancellation via the next heartbeat, at worst one heartbeat interval later.
 */
@Service
public class QueueManager {

    private static final Logger log = LoggerFactory.getLogger(QueueManager.class);

    private static final Set<ReviewStatus> TERMINAL_NON_RUNNABLE = Set.of(ReviewStatus.CANCELLED, ReviewStatus.OBSOLETE);

    private final ReviewRepository reviewRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final ReviewChunkRepository reviewChunkRepository;
    private final ReviewPromptSectionRepository reviewPromptSectionRepository;
    private final BackendDispatcher backendDispatcher;
    private final JobStateMachine jobStateMachine;
    private final ChunkCoordinator chunkCoordinator;
    private final EventService eventService;
    private final ResultProcessor resultProcessor;
    private final ChunkContextRenderer chunkContextRenderer;
    private final PromptMessageFormatter promptMessageFormatter;
    private final RetryManager retryManager;
    private final TextSanitizer textSanitizer;
    private final MetricsCounters metricsCounters;
    private final ReviewSchemaBuilder reviewSchemaBuilder;
    private final DecoderConstraintRenderer decoderConstraintRenderer;
    private final GatewayProperties properties;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public QueueManager(ReviewRepository reviewRepository,
                         ReviewJobRepository reviewJobRepository,
                         ReviewChunkRepository reviewChunkRepository,
                         ReviewPromptSectionRepository reviewPromptSectionRepository,
                         BackendDispatcher backendDispatcher,
                         JobStateMachine jobStateMachine,
                         ChunkCoordinator chunkCoordinator,
                         EventService eventService,
                         ResultProcessor resultProcessor,
                         ChunkContextRenderer chunkContextRenderer,
                         PromptMessageFormatter promptMessageFormatter,
                         RetryManager retryManager,
                         TextSanitizer textSanitizer,
                         MetricsCounters metricsCounters,
                         ReviewSchemaBuilder reviewSchemaBuilder,
                         DecoderConstraintRenderer decoderConstraintRenderer,
                         GatewayProperties properties,
                         EntityManager entityManager,
                         PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.reviewChunkRepository = reviewChunkRepository;
        this.reviewPromptSectionRepository = reviewPromptSectionRepository;
        this.backendDispatcher = backendDispatcher;
        this.jobStateMachine = jobStateMachine;
        this.chunkCoordinator = chunkCoordinator;
        this.eventService = eventService;
        this.resultProcessor = resultProcessor;
        this.chunkContextRenderer = chunkContextRenderer;
        this.promptMessageFormatter = promptMessageFormatter;
        this.retryManager = retryManager;
        this.textSanitizer = textSanitizer;
        this.metricsCounters = metricsCounters;
        this.reviewSchemaBuilder = reviewSchemaBuilder;
        this.decoderConstraintRenderer = decoderConstraintRenderer;
        this.properties = properties;
        this.entityManager = entityManager;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        this.requiresNewTransactionTemplate.setName("QueueManager.claimJobRow");
    }

    /**
     * Claims the next queued chunk job for {@code backendName}. Deliberately a plain (non-{@code
     * @Transactional}) orchestrating method: each step below is its own, independently-committed
     * transaction (CSR-17) — never one big transaction spanning both the job-row lock and the
     * parent-row lock.
     *
     * @return the claimed job's payload, or empty if there is nothing to claim right now (204) —
     *         which also covers "backend unknown", "backend not ACTIVE", "backend at capacity", and a
     *         lock-timeout on the job-row lock (all mapped to the same empty/204 outcome).
     */
    public Optional<ClaimedJob> claim(String backendName, String workerId) {
        ClaimAttempt attempt;
        try {
            attempt = requiresNewTransactionTemplate.execute(status -> claimJobRow(backendName, workerId));
        } catch (QueryTimeoutException | PessimisticLockingFailureException lockTimeout) {
            log.debug("Claim lock-timed-out for backend '{}': treating as no job available", backendName);
            return Optional.empty();
        }
        if (attempt == null || attempt.reviewIdTouched() == null) {
            return Optional.empty();
        }

        // CSR-17: independent transaction, parent lock only, taken after claimJobRow's transaction
        // (and the job-row lock it held) has already committed. Always run once a job row was actually
        // touched, so the parent's derived status never goes stale. PMR-09 note: at this point the job
        // row is RUNNING even on the fail-closed path below (claimJobRow never marks it FAILED itself —
        // see that method's javadoc for why) — this call is what legally advances the parent
        // QUEUED -> RUNNING before the follow-up fail step below asks for the (legal) RUNNING -> FAILED.
        chunkCoordinator.recomputeAndApply(attempt.reviewIdTouched());

        if (attempt.failJobId() != null) {
            // PMR-09 shape (also reused by SRO-67b/SRO-15): fail the job now that the parent is (legally)
            // RUNNING, then recompute once more so the parent (legally) follows RUNNING -> FAILED. Never
            // dispatched to the Worker.
            switch (attempt.failReason()) {
                case MISSING_PROMPT_SECTIONS -> failJobForMissingPromptSections(attempt.failJobId());
                case EMPTY_COVERAGE_LIST -> failJobForEmptyCoverageList(attempt.failJobId());
                case SCHEMA_TOO_LARGE -> failJobForSchemaTooLarge(attempt.failJobId());
            }
            chunkCoordinator.recomputeAndApply(attempt.reviewIdTouched());
            return Optional.empty();
        }

        ClaimedJob job = attempt.claimed().orElseThrow();

        // Lock-free "doomed job" courtesy check (best-effort, not a correctness mechanism -- see class
        // javadoc): if the parent already went CANCELLED/OBSOLETE, don't hand this job to the Worker.
        ReviewStatus reviewStatus = reviewRepository.findById(job.reviewId()).map(Review::getStatus).orElse(null);
        if (TERMINAL_NON_RUNNABLE.contains(reviewStatus)) {
            markDoomedJob(job.jobId(), reviewStatus);
            return Optional.empty();
        }
        return attempt.claimed();
    }

    /** Why {@link #claim} must defer-fail a job rather than dispatch it (see {@link ClaimAttempt}). */
    private enum ClaimFailReason {
        /** PMR-09: {@code prompt_bundle_mode=REPO} but mandatory CORPORATE_* sections are missing. */
        MISSING_PROMPT_SECTIONS,
        /** SRO-67b: a structured job whose parsed {@code file_paths} is empty. */
        EMPTY_COVERAGE_LIST,
        /** SRO-15: backstop — a rendered schema exceeded {@code gateway.structured.max-schema-bytes}. */
        SCHEMA_TOO_LARGE
    }

    /**
     * Outcome of {@link #claimJobRow}: {@code reviewIdTouched} is set whenever a job row's status
     * actually changed, so {@link #claim} knows to recompute the parent's derived status;
     * {@code failJobId}/{@code failReason} are set instead of {@code claimed} on a fail-closed path (the
     * job is RUNNING at this point — see {@link #claimJobRow}'s javadoc for why it is not marked FAILED
     * there directly). {@code null} {@code reviewIdTouched} means nothing was touched at all (no
     * claimable backend, empty queue).
     */
    private record ClaimAttempt(Optional<ClaimedJob> claimed, Long reviewIdTouched, Long failJobId, ClaimFailReason failReason) {

        static ClaimAttempt none() {
            return new ClaimAttempt(Optional.empty(), null, null, null);
        }

        static ClaimAttempt claimed(ClaimedJob job) {
            return new ClaimAttempt(Optional.of(job), job.reviewId(), null, null);
        }

        static ClaimAttempt missingPromptSections(Long jobId, Long reviewId) {
            return new ClaimAttempt(Optional.empty(), reviewId, jobId, ClaimFailReason.MISSING_PROMPT_SECTIONS);
        }

        static ClaimAttempt emptyCoverageList(Long jobId, Long reviewId) {
            return new ClaimAttempt(Optional.empty(), reviewId, jobId, ClaimFailReason.EMPTY_COVERAGE_LIST);
        }

        static ClaimAttempt schemaTooLarge(Long jobId, Long reviewId) {
            return new ClaimAttempt(Optional.empty(), reviewId, jobId, ClaimFailReason.SCHEMA_TOO_LARGE);
        }
    }

    /** Phase 1: locks only the job row (CSR-17). Runs inside {@link #requiresNewTransactionTemplate}. */
    private ClaimAttempt claimJobRow(String backendName, String workerId) {
        applyLockTimeout();
        Optional<Backend> backendOpt = backendDispatcher.resolveClaimableBackend(backendName);
        if (backendOpt.isEmpty()) {
            log.debug("Claim declined for backend '{}': not claimable right now", backendName);
            return ClaimAttempt.none();
        }
        Backend backend = backendOpt.get();

        Optional<Long> jobIdOpt = reviewJobRepository.findNextQueuedJobIdForUpdate();
        if (jobIdOpt.isEmpty()) {
            return ClaimAttempt.none();
        }
        Long jobId = jobIdOpt.get();

        ReviewJob job = reviewJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Claimed job " + jobId + " vanished within its own transaction"));

        job.incrementAttempts();
        Instant now = Instant.now();
        job.setBackendId(backend.getId());
        job.setWorkerId(workerId);
        job.setHeartbeatAt(now);
        job.setClaimedAt(now);
        job.setStartedAt(now);
        job.setFinishedAt(null);
        job.setLastError(null);
        job.setNotBefore(null);
        jobStateMachine.transition(job, JobStatus.RUNNING, EventType.CLAIMED, workerId, backend.getId(),
                "attempt=" + job.getAttempts());
        reviewJobRepository.save(job);
        eventService.record(job.getReviewId(), EventType.RUNNING, workerId, backend.getId(),
                job.getChunkIndex(), job.getId(), "execution started");

        ReviewChunk chunk = reviewChunkRepository.findByReviewIdAndChunkIndex(job.getReviewId(), job.getChunkIndex())
                .orElseThrow(() -> new IllegalStateException(
                        "Job " + jobId + " references missing review_chunks row (reviewId=" + job.getReviewId()
                                + ", chunkIndex=" + job.getChunkIndex() + ")"));

        Review review = reviewRepository.findById(job.getReviewId())
                .orElseThrow(() -> new IllegalStateException("Review " + job.getReviewId() + " vanished mid-claim"));

        boolean structuredVersion = StructuredOutputSupport.isStructured(review.getPromptVersion());

        // SRO-64c: ONE parse, ONE List<String> instance, fed to both the prompt renderer and the schema
        // builder below -- divergence between the two must be impossible by construction.
        List<String> thisChunkPaths = parseFilePaths(chunk.getFilePaths());

        if (structuredVersion && thisChunkPaths.isEmpty()) {
            // SRO-67b: fail closed immediately -- never dispatch a structured job whose own coverage list
            // is unreadable; no number of retries changes what a chunk row's file_paths column holds, so
            // this is NOT routed through RetryManager (SOT-14: retrying would only burn LLM compute).
            log.warn("Claim-time COVERAGE_LIST_UNAVAILABLE: jobId={} reviewId={} chunkIndex={}: parsed "
                    + "file_paths is empty/unreadable", job.getId(), job.getReviewId(), job.getChunkIndex());
            return ClaimAttempt.emptyCoverageList(job.getId(), job.getReviewId());
        }

        String chunkContext = buildChunkContext(review.getId(), chunk, thisChunkPaths, structuredVersion);

        List<String> systemMessages;
        try {
            List<ReviewPromptSection> sections =
                    reviewPromptSectionRepository.findByReviewIdOrderByOrdinalAsc(job.getReviewId());
            systemMessages = promptMessageFormatter.render(
                    review.getPromptBundleMode(), sections, backend.getPromptMessageFormat());
        } catch (PromptSectionsMissingException missingSections) {
            // PMR-09: fail-closed, never dispatch a REPO-mode job with an empty/partial system prompt.
            // Deliberately does NOT mark the job FAILED here: the job is RUNNING and the *parent*
            // Review's own status is still whatever it was before this claim (typically QUEUED, for a
            // Review's very first chunk) -- Review-level QUEUED -> FAILED is not a legal StateMachine
            // transition (only RUNNING -> FAILED is), and this job-lock-only transaction must never take
            // the parent lock itself (CSR-17). {@link #claim} performs the actual fail step as a separate
            // follow-up transaction, only after its own recompute call has already legally advanced the
            // parent to RUNNING.
            log.warn("Claim-time PROMPT_SECTIONS_MISSING: jobId={} reviewId={}: {}",
                    job.getId(), job.getReviewId(), missingSections.getMessage());
            return ClaimAttempt.missingPromptSections(job.getId(), job.getReviewId());
        }

        // Structured Review Output (architecture §4.4): the coverage list is rendered into the prompt
        // (above) whether or not a constraint is built here -- SRO-64 is keyed on the prompt VERSION,
        // this block's mode gate only decides whether a decoder-level CONSTRAINT is additionally built.
        String responseFormat = null;
        String jsonSchema = null;
        if (structuredVersion && !properties.getStructured().isEnabled()) {
            // SRO-45: keeps the kill-switch-off population visible in the same metric, distinguishing
            // "the model is good" from "we never turned it on" at a glance.
            metricsCounters.incrementStructuredConstraintSent("KILL_SWITCH_OFF");
        }
        if (structuredVersion && properties.getStructured().isEnabled()) {
            StructuredOutputMode mode = decoderConstraintRenderer.resolveMode(
                    backend.getStructuredOutputMode(), properties.getStructured().getDefaultMode());
            metricsCounters.incrementStructuredConstraintSent(mode.name());
            if (mode != StructuredOutputMode.OFF) {
                String schema = reviewSchemaBuilder.build(thisChunkPaths, structuredSchemaOptions());
                int schemaBytes = schema.getBytes(StandardCharsets.UTF_8).length;
                int maxSchemaBytes = properties.getStructured().getMaxSchemaBytes();
                if (schemaBytes > maxSchemaBytes) {
                    // SRO-15: backstop only -- with SRO-65/SRO-66 in force this is unreachable; if it
                    // fires, that is a bug in the edge validation, and last_error says so verbatim.
                    log.error("Claim-time SCHEMA_TOO_LARGE (edge bound did not fire -- bug): jobId={} "
                                    + "reviewId={} chunkIndex={} schemaBytes={} max={}",
                            job.getId(), job.getReviewId(), job.getChunkIndex(), schemaBytes, maxSchemaBytes);
                    return ClaimAttempt.schemaTooLarge(job.getId(), job.getReviewId());
                }
                DecoderConstraintRenderer.DecoderConstraint constraint = decoderConstraintRenderer.render(schema, mode);
                responseFormat = constraint.responseFormat();
                jsonSchema = constraint.jsonSchema();
            }
        }

        log.info("Job claimed: jobId={} reviewId={} chunkIndex={} backend={} workerId={}",
                job.getId(), job.getReviewId(), job.getChunkIndex(), backendName, workerId);
        ClaimedJob claimedJob = new ClaimedJob(job.getId(), job.getReviewId(), chunk.getDiff(),
                review.getPromptVersion(), chunkContext, systemMessages, responseFormat, jsonSchema);
        return ClaimAttempt.claimed(claimedJob);
    }

    private ReviewSchemaBuilder.SchemaOptions structuredSchemaOptions() {
        GatewayProperties.Structured cfg = properties.getStructured();
        return new ReviewSchemaBuilder.SchemaOptions(cfg.getMaxFindingsPerFile(), cfg.getMaxCommentChars(),
                cfg.getMaxSuggestionChars(), cfg.isPerFileSummary());
    }

    /**
     * PMR-09 follow-up fail step (called from {@link #claim} only after the parent Review has already
     * been recomputed to {@code RUNNING}, making this job's own {@code RUNNING -> FAILED} transition
     * legal at the Review level too on the next recompute). Its own small transaction, job-row lock
     * only — mirrors {@link RetryManager#requeueOrFailJobOnly}'s pattern exactly.
     */
    private void failJobForMissingPromptSections(Long jobId) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            applyLockTimeout();
            ReviewJob job = reviewJobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus() != JobStatus.RUNNING) {
                return;
            }
            jobStateMachine.transition(job, JobStatus.FAILED, EventType.PROMPT_SECTIONS_MISSING, job.getWorkerId(),
                    job.getBackendId(), "prompt_bundle_mode=REPO but mandatory CORPORATE_* sections missing at claim time");
            reviewJobRepository.save(job);
            log.info("Job {} FAILED at claim time: mandatory CORPORATE_* prompt sections missing (reviewId={})",
                    jobId, job.getReviewId());
        });
    }

    /** SRO-40/SRO-67b: no new {@code EventType} — the origin lives in this Gateway-constant prefix. */
    private static final String COVERAGE_LIST_UNAVAILABLE_ERROR = "structured-output: COVERAGE_LIST_UNAVAILABLE";
    /** SRO-15/SRO-40: ditto — the backstop firing is itself the diagnosis. */
    private static final String SCHEMA_TOO_LARGE_ERROR =
            "structured-output: SCHEMA_TOO_LARGE (edge bound did not fire -- bug)";

    /**
     * SRO-67b follow-up fail step, structurally identical to {@link #failJobForMissingPromptSections}
     * (own {@code REQUIRES_NEW} transaction, job-row lock only, called from {@link #claim} only after
     * the parent has already been recomputed to {@code RUNNING}) — the two deliberate differences from
     * PMR-09 are the reused {@code EventType.FAILED} (SRO-40: no new event type) and the origin living in
     * {@code last_error}/{@code details}' Gateway-constant prefix instead. Never requeued: a chunk row
     * that cannot yield its own file list will not yield one on attempt 2 either (SOT-14).
     */
    private void failJobForEmptyCoverageList(Long jobId) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            applyLockTimeout();
            ReviewJob job = reviewJobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus() != JobStatus.RUNNING) {
                return;
            }
            job.setLastError(COVERAGE_LIST_UNAVAILABLE_ERROR);
            jobStateMachine.transition(job, JobStatus.FAILED, EventType.FAILED, job.getWorkerId(),
                    job.getBackendId(), COVERAGE_LIST_UNAVAILABLE_ERROR);
            reviewJobRepository.save(job);
            log.info("Job {} FAILED at claim time: structured-output coverage list unavailable (reviewId={})",
                    jobId, job.getReviewId());
        });
    }

    /**
     * SRO-15 backstop follow-up fail step — same shape as {@link #failJobForEmptyCoverageList}. Should be
     * unreachable given the SRO-65/SRO-66 edge bounds; if it ever fires, that is a bug in those bounds,
     * not a legitimate per-Review outcome, hence the {@code ERROR}-level log.
     */
    private void failJobForSchemaTooLarge(Long jobId) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            applyLockTimeout();
            ReviewJob job = reviewJobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus() != JobStatus.RUNNING) {
                return;
            }
            job.setLastError(SCHEMA_TOO_LARGE_ERROR);
            jobStateMachine.transition(job, JobStatus.FAILED, EventType.FAILED, job.getWorkerId(),
                    job.getBackendId(), SCHEMA_TOO_LARGE_ERROR);
            reviewJobRepository.save(job);
            log.error("Job {} FAILED at claim time: rendered schema exceeded gateway.structured.max-schema-bytes "
                    + "(reviewId={}) -- indicates a bug in the SRO-65/SRO-66 edge validation, not a per-Review issue",
                    jobId, job.getReviewId());
        });
    }

    /**
     * Renders the cross-chunk context header (§3). For a non-structured version, {@code null} whenever
     * this Review has only one chunk (single-chunk equivalence, §8) — unchanged. For a structured
     * version (SRO-64a), <b>never</b> {@code null}, regardless of chunk count: the schema built from the
     * same {@code thisChunkPaths} instance always demands this exact key set, so the model must always be
     * shown it, even when the decoder constraint itself is not applied.
     *
     * @param thisChunkPaths the SRO-64c single parsed instance for this chunk — never re-parsed here
     */
    private String buildChunkContext(Long reviewId, ReviewChunk chunk, List<String> thisChunkPaths, boolean structuredVersion) {
        Integer chunkCount = chunk.getChunkCount();
        int effectiveChunkCount = chunkCount == null ? 1 : chunkCount;

        if (!structuredVersion && effectiveChunkCount <= 1) {
            return null;
        }

        List<String> otherPaths = effectiveChunkCount > 1
                ? collectOtherChunkPaths(reviewId, chunk.getChunkIndex())
                : List.of();

        if (structuredVersion) {
            return chunkContextRenderer.renderStructured(chunk.getChunkIndex(), effectiveChunkCount, thisChunkPaths, otherPaths);
        }
        return chunkContextRenderer.render(chunk.getChunkIndex(), effectiveChunkCount, thisChunkPaths, otherPaths);
    }

    private List<String> collectOtherChunkPaths(Long reviewId, Integer excludeChunkIndex) {
        List<ReviewChunk> allChunks = reviewChunkRepository.findByReviewIdOrderByChunkIndexAsc(reviewId);
        List<String> otherPaths = new ArrayList<>();
        for (ReviewChunk other : allChunks) {
            if (!other.getChunkIndex().equals(excludeChunkIndex)) {
                otherPaths.addAll(parseFilePaths(other.getFilePaths()));
            }
        }
        return otherPaths;
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
            log.warn("review_chunks.file_paths was not valid JSON (length={}); rendering no file paths for this chunk",
                    json.length());
            return List.of();
        }
    }

    /**
     * Marks a "doomed" job (parent already CANCELLED/OBSOLETE) with the matching status instead of
     * dispatching it to the Worker. Its own small transaction, job-row lock only.
     *
     * <p>F-DC-05: applies its own {@code lock_timeout} — this is a genuinely separate {@code
     * REQUIRES_NEW} transaction from {@link #claimJobRow}, so the {@code SET LOCAL} that transaction
     * applied does not carry over; every transaction that takes a lock needs its own.
     */
    private void markDoomedJob(Long jobId, ReviewStatus reviewStatus) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            applyLockTimeout();
            ReviewJob job = reviewJobRepository.findByIdForUpdate(jobId).orElse(null);
            if (job == null || job.getStatus() != JobStatus.RUNNING) {
                return;
            }
            JobStatus target = reviewStatus == ReviewStatus.CANCELLED ? JobStatus.CANCELLED : JobStatus.OBSOLETE;
            EventType eventType = reviewStatus == ReviewStatus.CANCELLED ? EventType.CANCELLED : EventType.OBSOLETE;
            jobStateMachine.transition(job, target, eventType, job.getWorkerId(), job.getBackendId(),
                    "parent review already " + reviewStatus + " at claim time");
            reviewJobRepository.save(job);
            log.info("Job {} marked {} at claim time (parent reviewId={} already {})",
                    jobId, target, job.getReviewId(), reviewStatus);
        });
    }

    /**
     * Handles {@code POST /jobs/{id}/heartbeat} (req. 1.7, SR-04). Ownership is checked before
     * anything is mutated. {@code shouldContinue} is {@code (job.status == RUNNING) && (review.status
     * == RUNNING)} — the job-row lock taken to update the heartbeat is sufficient for the job read; the
     * review-status read is a plain (unlocked) read, which is safe here since it's advisory (the
     * Worker will simply see the same answer again on its next heartbeat if this one raced).
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
            metricsCounters.incrementOwnershipMismatch("heartbeat");
            return HeartbeatResult.ownershipMismatch();
        }

        if (job.getStatus() != JobStatus.RUNNING) {
            log.debug("Heartbeat for jobId={} but job is {} (not RUNNING) -> shouldContinue=false", jobId, job.getStatus());
            return HeartbeatResult.accepted(false);
        }

        ReviewStatus reviewStatus = reviewRepository.findById(job.getReviewId()).map(Review::getStatus).orElse(null);
        boolean shouldContinue = reviewStatus == ReviewStatus.RUNNING;

        job.setHeartbeatAt(Instant.now());
        reviewJobRepository.save(job);
        eventService.record(job.getReviewId(), EventType.HEARTBEAT, workerId, job.getBackendId(),
                job.getChunkIndex(), job.getId(), null);
        return HeartbeatResult.accepted(shouldContinue);
    }

    /**
     * Handles {@code POST /jobs/{id}/result} (req. 1.9, SR-04, SR-21). Idempotent: if the JOB is no
     * longer {@code RUNNING} the submission is acknowledged without changing any state.
     */
    @Transactional
    public SubmitResultOutcome submitResult(Long jobId, String workerId, SubmitResultCommand command) {
        Optional<ReviewJob> jobOpt = reviewJobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.debug("Result submission for unknown jobId={}", jobId);
            return SubmitResultOutcome.notFound();
        }
        ReviewJob job = jobOpt.get();

        if (!isOwner(job, workerId)) {
            log.warn("Result submission ownership mismatch: jobId={} claimedBy={} callerWorkerId={}", jobId, job.getWorkerId(), workerId);
            metricsCounters.incrementOwnershipMismatch("result");
            // F02-05: do not echo the review's status to a non-owner -- indistinguishable from NOT_FOUND.
            return SubmitResultOutcome.ownershipMismatch();
        }

        Review review = reviewRepository.findById(job.getReviewId())
                .orElseThrow(() -> new IllegalStateException("Job " + jobId + " references missing review " + job.getReviewId()));

        if (job.getStatus() != JobStatus.RUNNING) {
            log.info("Idempotent no-op result submission: jobId={} reviewId={} currentJobStatus={}",
                    jobId, review.getId(), job.getStatus());
            return SubmitResultOutcome.idempotentNoop(review.getId(), review.getStatus());
        }

        ReviewStatus finalStatus = resultProcessor.process(review.getId(), job.getId(), workerId, job.getBackendId(), command);
        return SubmitResultOutcome.accepted(review.getId(), finalStatus);
    }

    /** WOC-25/WOR-06c: {@code detail} is sanitized to a single line, capped at 200 chars. */
    private static final int MAX_DETAIL_LENGTH = 200;

    /**
     * Handles {@code POST /jobs/{id}/fail} (architecture §5.2, WOC-26). Deliberately <b>not</b> {@code
     * @Transactional} (unlike {@link #heartbeat}/{@link #submitResult}): {@link RetryManager#requeueOrFail}
     * opens its own {@code REQUIRES_NEW} transaction and, only after that commits, calls
     * {@code ChunkCoordinator} for the parent-row lock. An outer transaction here would hold a Hikari
     * connection across both phases and reintroduce exactly the lock-ordering hazard CSR-17 removed
     * (see {@code RetryManager.requeueOrFail}'s own "plain orchestrating method" precedent).
     *
     * <p>The unlocked pre-check below exists only to return {@code 404}/{@code 403} cheaply and to avoid
     * opening a write transaction for the common no-op case (WOR-15b: the job is already not {@code
     * RUNNING}); it is never the authorization decision. {@link RetryManager} re-checks ownership under
     * the job-row lock (WOC-27) and that locked result always wins (WOR-19) — if the two disagree
     * (a genuine race), the response reflects the locked outcome.
     *
     * @param reasonCode whitelist-parsed against {@link JobFailureReason} (WOC-23); an unrecognized value
     *                    maps to {@code UNKNOWN} with a WARN, never a {@code 400} — audit-only, never
     *                    influences the retry-vs-fail decision (WOC-24).
     * @param detail      untrusted, Worker-supplied free text; sanitized to a single line and truncated to
     *                    {@value #MAX_DETAIL_LENGTH} chars <b>here, at ingress</b>, before it ever reaches
     *                    {@link RetryManager}, a logger, {@code review_events}, or {@code last_error}
     *                    (WOC-25/WOR-06c) — the raw value never propagates past this method.
     */
    public FailureReportOutcome reportFailure(Long jobId, String workerId, String reasonCode, String detail) {
        Optional<ReviewJob> preCheck = reviewJobRepository.findById(jobId);
        if (preCheck.isEmpty()) {
            log.debug("Failure report for unknown jobId={}", jobId);
            return FailureReportOutcome.NOT_FOUND;
        }
        ReviewJob job = preCheck.get();
        if (!isOwner(job, workerId)) {
            log.warn("Failure report ownership mismatch: jobId={} claimedBy={} callerWorkerId={}",
                    jobId, job.getWorkerId(), workerId);
            metricsCounters.incrementOwnershipMismatch("fail");
            metricsCounters.incrementWorkerFailureReportsIgnored();
            return FailureReportOutcome.OWNERSHIP_MISMATCH;
        }
        if (job.getStatus() != JobStatus.RUNNING) {
            // WOR-15b: no-op path returns 200 without opening RetryManager's REQUIRES_NEW transaction.
            log.info("Idempotent no-op failure report: jobId={} currentJobStatus={}", jobId, job.getStatus());
            metricsCounters.incrementWorkerFailureReportsIgnored();
            return FailureReportOutcome.ACCEPTED;
        }

        JobFailureReason reason = JobFailureReason.fromWireValue(reasonCode);
        if (reason == JobFailureReason.UNKNOWN) {
            // WOC-23: never log the raw value, only its length -- it is untrusted, caller-supplied text.
            log.warn("Unknown worker-reported failure reason (length={}) for jobId={}",
                    reasonCode == null ? 0 : reasonCode.length(), jobId);
        }
        String sanitizedDetail = textSanitizer.sanitizeSingleLine(detail, MAX_DETAIL_LENGTH);
        String composedReason = "worker-reported: reason=" + reason
                + (sanitizedDetail != null ? "; detail=" + sanitizedDetail : "");

        RequeueOutcome outcome;
        try {
            outcome = retryManager.requeueOrFail(jobId, composedReason, workerId);
        } catch (PessimisticLockingFailureException | QueryTimeoutException lockTimeout) {
            // WOC-32: never surface a lock-timeout as a 500 to the Worker -- the stale-heartbeat sweep
            // remains the correctness backstop for this best-effort, latency-only optimization.
            log.debug("Failure report lock-timed-out for jobId={}: the sweep will handle it", jobId);
            return FailureReportOutcome.ACCEPTED;
        }

        return switch (outcome.outcome()) {
            case NOT_FOUND -> FailureReportOutcome.NOT_FOUND;
            case OWNERSHIP_MISMATCH -> {
                // WOR-19: the locked re-check disagreed with the unlocked pre-check above (a genuine
                // race) -- the locked outcome wins.
                log.warn("Failure report ownership mismatch under lock: jobId={} callerWorkerId={}", jobId, workerId);
                metricsCounters.incrementOwnershipMismatch("fail");
                metricsCounters.incrementWorkerFailureReportsIgnored();
                yield FailureReportOutcome.OWNERSHIP_MISMATCH;
            }
            case NOOP_NOT_RUNNING -> {
                metricsCounters.incrementWorkerFailureReportsIgnored();
                yield FailureReportOutcome.ACCEPTED;
            }
            case APPLIED_REQUEUED, APPLIED_FAILED -> {
                log.info("Worker-reported job failure: jobId={} workerId={} reason={}", jobId, workerId, reason);
                yield FailureReportOutcome.ACCEPTED;
            }
        };
    }

    private boolean isOwner(ReviewJob job, String workerId) {
        return workerId != null && workerId.equals(job.getWorkerId());
    }

    /**
     * CSR-17: bounds how long the claim's job-row lock wait can pin a Hikari connection (pool size 20).
     */
    private void applyLockTimeout() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
    }
}

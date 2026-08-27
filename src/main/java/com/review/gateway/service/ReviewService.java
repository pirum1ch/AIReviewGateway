package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.IncompatiblePromptVersionException;
import com.review.gateway.exception.InvalidStateTransitionException;
import com.review.gateway.exception.ReviewNotFoundException;
import com.review.gateway.exception.StructuredOutputUnsupportedException;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewChunk;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.ReviewPromptSection;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.PromptBundleMode;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewPromptSectionRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.CreateReviewCommand;
import com.review.gateway.service.dto.CreateReviewResult;
import com.review.gateway.service.dto.ReviewStatusView;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates Review creation, status reads, and admin cancellation (architecture §2; V2, diff
 * chunking). Creation is deliberately split across independent transactions rather than one big one —
 * see the class-level javadoc history for the rationale, unchanged by V2.
 *
 * <p><b>CSR-17 (lock ordering):</b> {@link #cancel} and {@link #sweepObsolete} both lock the
 * <em>parent</em> {@code reviews} row first (individually, in a deterministic {@code ORDER BY id} for
 * the multi-row sweep — CSR-18), then cascade the same transition to every non-terminal child
 * {@code review_jobs} row. This is the parent-then-child-only propagation direction the fix requires;
 * no code path here waits on a job-row lock while still holding the parent lock. <b>F-DC-03:</b> the
 * parent lock itself is {@code FOR NO KEY UPDATE}, not {@code FOR UPDATE} — see
 * {@link com.review.gateway.repository.ReviewRepository#findByIdForNoKeyUpdate} for why a plain
 * {@code FOR UPDATE} here reintroduced a real deadlock via PostgreSQL's FK referential-integrity
 * trigger.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private static final Set<ReviewStatus> CANCELLABLE_STATUSES = EnumSet.of(
            ReviewStatus.NEW, ReviewStatus.QUEUED, ReviewStatus.RUNNING, ReviewStatus.COMPLETED);

    /**
     * CSR-12: Gateway-side allowlist of prompt versions known to contain the {@code {{CHUNK_CONTEXT}}}
     * placeholder. Only enforced when a Review needs more than one chunk — a single-chunk Review never
     * renders a chunk context, so any existing prompt version (e.g. {@code v1}) remains valid for it
     * (backward compatibility, §8). SRO-61: {@code v3} (Structured Review Output) is chunk-context-aware
     * too — {@code v3.yml} requires the placeholder unconditionally (SRO-64a).
     */
    private static final Set<String> CHUNK_AWARE_PROMPT_VERSIONS = Set.of("v2", "v3");

    private final ReviewRepository reviewRepository;
    private final ReviewInputRepository reviewInputRepository;
    private final ReviewChunkRepository reviewChunkRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final ReviewPromptSectionRepository reviewPromptSectionRepository;
    private final DeduplicationService deduplicationService;
    private final DiffSizeValidator diffSizeValidator;
    private final DiffChunker diffChunker;
    private final ChunkContextRenderer chunkContextRenderer;
    private final PromptManager promptManager;
    private final EventService eventService;
    private final StateMachine stateMachine;
    private final JobStateMachine jobStateMachine;
    private final StructuredPathValidator structuredPathValidator;
    private final GatewayProperties properties;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ReviewService(ReviewRepository reviewRepository,
                          ReviewInputRepository reviewInputRepository,
                          ReviewChunkRepository reviewChunkRepository,
                          ReviewJobRepository reviewJobRepository,
                          ReviewCommentRepository reviewCommentRepository,
                          ReviewPromptSectionRepository reviewPromptSectionRepository,
                          DeduplicationService deduplicationService,
                          DiffSizeValidator diffSizeValidator,
                          DiffChunker diffChunker,
                          ChunkContextRenderer chunkContextRenderer,
                          PromptManager promptManager,
                          EventService eventService,
                          StateMachine stateMachine,
                          JobStateMachine jobStateMachine,
                          StructuredPathValidator structuredPathValidator,
                          GatewayProperties properties,
                          EntityManager entityManager,
                          PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewInputRepository = reviewInputRepository;
        this.reviewChunkRepository = reviewChunkRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.reviewPromptSectionRepository = reviewPromptSectionRepository;
        this.deduplicationService = deduplicationService;
        this.diffSizeValidator = diffSizeValidator;
        this.diffChunker = diffChunker;
        this.chunkContextRenderer = chunkContextRenderer;
        this.promptManager = promptManager;
        this.eventService = eventService;
        this.stateMachine = stateMachine;
        this.jobStateMachine = jobStateMachine;
        this.structuredPathValidator = structuredPathValidator;
        this.properties = properties;
        this.entityManager = entityManager;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("ReviewService.persistNewReview");
    }

    /**
     * Creates a new Review, or returns the existing one for the same dedup key (req. 1.5). Order of
     * operations: cheap absurd-size guard (CSR-01, no DB access) -&gt; sweep prior non-terminal Reviews
     * of the MR to OBSOLETE if this head_sha is new -&gt; dedup lookup -&gt; resolve prompt sections
     * (Prompt Manager, architecture §3 — no point calling GitLab for a request that will be
     * deduplicated) -&gt; split into chunks (DiffChunker, budget reduced by the resolved system-prompt
     * size) -&gt; validate prompt-version compatibility if chunked (CSR-12) -&gt; insert (racing safely
     * against concurrent creates).
     */
    public CreateReviewResult createReview(CreateReviewCommand command) {
        // Threat model SOR-08 (CRITICAL): promptVersion is allowlisted at the very edge, before any
        // other work -- 'v3' is deliberately absent from the shipped default allowlist until an operator
        // adds it once every Worker in the fleet ships v3.yml (Workers-first deployment order, §11).
        validatePromptVersionAllowlist(command.promptVersion());

        diffSizeValidator.rejectIfAbsurdlyLarge(command.diff());

        sweepObsolete(command.projectId(), command.mergeRequestId(), command.headSha());

        var existing = deduplicationService.findActiveReview(
                command.projectId(), command.mergeRequestId(), command.headSha());
        if (existing.isPresent()) {
            log.info("Review create deduplicated: projectId={} mrId={} headSha={} -> existing reviewId={}",
                    command.projectId(), command.mergeRequestId(), command.headSha(), existing.get().getId());
            return toResult(existing.get(), true);
        }

        // Never inside a DB transaction (architecture §3): a GitLab HTTP call must never hold a Hikari
        // connection or a row lock.
        PromptManager.PromptResolution promptResolution = promptManager.resolve(command.projectId());

        // F-SRO-03: the answer reserve must size BOTH this pre-chunking prompt-fits check and
        // DiffChunker.split's runtime chunk budget below -- computed once, before either call, so they
        // cannot diverge from each other or from the startup assertion
        // (GatewayProperties.validateStructuredOnStartup) that already checks this same arithmetic.
        // chore/answer-reserve-consolidation: a single gateway.diff.answer-reserve now sizes both v1/v2
        // and structured (v3) responses -- no branch needed.
        boolean structured = StructuredOutputSupport.isStructured(command.promptVersion());
        int answerReserveTokens = properties.getDiff().getAnswerReserve();
        diffSizeValidator.assertPromptFits(promptResolution.estimatedTokens(), answerReserveTokens);

        int maxFilesPerChunk = structured ? properties.getStructured().getMaxFilesPerChunk() : 0;
        DiffChunker.ChunkPlan plan = diffChunker.split(command.diff(), promptResolution.estimatedTokens(), maxFilesPerChunk);
        if (plan.chunks().size() > 1) {
            validatePromptVersionForChunking(command.promptVersion());
        }
        if (structured) {
            validateStructuredOutputEligibility(command.promptVersion(), plan);
        }

        try {
            Review created = requiresNewTransactionTemplate.execute(
                    status -> persistNewReview(command, plan, promptResolution));
            log.info("Review created: reviewId={} projectId={} mrId={} headSha={} chunks={} promptBundleMode={}",
                    created.getId(), command.projectId(), command.mergeRequestId(), command.headSha(),
                    plan.chunks().size(), promptResolution.mode());
            return new CreateReviewResult(created.getId(), created.getStatus(), false, plan.chunks().size());
        } catch (DataIntegrityViolationException race) {
            log.info("Review create race detected (unique-violation), re-reading existing: projectId={} mrId={} headSha={}",
                    command.projectId(), command.mergeRequestId(), command.headSha());
            Review winner = deduplicationService.findActiveReview(
                            command.projectId(), command.mergeRequestId(), command.headSha())
                    .orElseThrow(() -> race);
            return toResult(winner, true);
        }
    }

    /** CSR-12 (Gateway side): fail-closed if a chunked Review's prompt version predates chunk-context support. */
    private void validatePromptVersionForChunking(String promptVersion) {
        if (!CHUNK_AWARE_PROMPT_VERSIONS.contains(promptVersion)) {
            throw new IncompatiblePromptVersionException(
                    "promptVersion '" + promptVersion + "' is not chunk-context-aware; this diff requires chunking. "
                            + "Use one of " + CHUNK_AWARE_PROMPT_VERSIONS + " or submit a smaller diff.");
        }
    }

    /**
     * Threat model SOR-08 (CRITICAL): {@code promptVersion} must be in {@code
     * gateway.review.allowed-prompt-versions} — an old Worker fleet meeting a {@code v3} job before it
     * ships {@code v3.yml} would abandon it and burn all three attempts in ~3 minutes (WOC's immediate
     * {@code POST /jobs/{id}/fail}), failing the Review with the sibling cascade. Reuses {@code 422
     * STRUCTURED_OUTPUT_UNSUPPORTED} (no new error code, architecture §4.3 preamble).
     *
     * <p><b>F-SRO-09 (appsec SAST fix round):</b> never echoes the caller-supplied {@code promptVersion}
     * back into the response body — {@code CreateReviewRequest.promptVersion} now has an edge-level
     * {@code @Size}/{@code @Pattern} bound, but this throw site itself still names only the allowed set
     * (a Gateway-config value, not attacker-controlled), matching the discipline the SRO-16/17/65 throw
     * sites in {@link #validateStructuredOutputEligibility} already follow.
     */
    private void validatePromptVersionAllowlist(String promptVersion) {
        if (!properties.getReview().getAllowedPromptVersions().contains(promptVersion)) {
            throw new StructuredOutputUnsupportedException(
                    "promptVersion is not in the allowlist gateway.review.allowed-prompt-versions="
                            + properties.getReview().getAllowedPromptVersions()
                            + "; an operator enables a new version only once every Worker in the fleet can serve it");
        }
    }

    /**
     * Structured Review Output (architecture §4.3, SRO-16/17/65; threat model SOT-02/07/15/22/24,
     * SOR-01, BLOCKING). Fail-fast at the edge, before any Review/chunk/job is persisted — a structured
     * Review whose coverage guarantee would otherwise be weaker than advertised is rejected wholesale,
     * never silently degraded.
     */
    private void validateStructuredOutputEligibility(String promptVersion, DiffChunker.ChunkPlan plan) {
        if (!plan.pathsTrusted()) {
            // SRO-16: the CSR-11 fallback (no `diff --git` headers) extracts no paths at all -- a
            // structured schema built from an empty set would have no coverage constraint whatsoever.
            throw new StructuredOutputUnsupportedException(
                    "promptVersion '" + promptVersion + "' requires git-style `diff --git` headers to derive "
                            + "the per-file coverage list; submit a `git diff` or use promptVersion 'v2'.");
        }
        int maxPathChars = properties.getStructured().getMaxPathChars();
        for (DiffChunker.DiffChunk chunk : plan.chunks()) {
            List<String> sanitizedPaths = chunk.filePaths().stream()
                    .map(chunkContextRenderer::sanitizePath)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            // F-SRO-02: compare DISTINCT sanitized values against the raw count, not just the size.
            // sanitizePath is not injective (it strips control/format chars and '<'/'>' and trim()s), so
            // two different raw paths can sanitize to the same string while both remain non-null -- a
            // plain size comparison misses that collision entirely. A collision must trip the same
            // fail-closed path as a dropped path: either way, one file silently loses its coverage
            // guarantee.
            if (sanitizedPaths.size() != chunk.filePaths().size()
                    || new LinkedHashSet<>(sanitizedPaths).size() != sanitizedPaths.size()) {
                // SRO-17: sanitization dropped a path (control/format characters only), or two distinct
                // paths collided onto the same sanitized value -- either way a file is left uncovered.
                // Never echo the raw or sanitized path back to the caller.
                throw new StructuredOutputUnsupportedException(
                        "promptVersion '" + promptVersion + "' requires every changed file path to survive "
                                + "sanitization and remain distinct from every other path; this diff contains "
                                + "at least one path that does not — use promptVersion 'v2' or rename the "
                                + "affected file(s).");
            }
            for (String path : sanitizedPaths) {
                if (!structuredPathValidator.isEligible(path, maxPathChars)) {
                    // SRO-65: a conservative, constrained alphabet -- never echoes the offending path,
                    // only the character class it violated.
                    throw new StructuredOutputUnsupportedException(
                            "promptVersion '" + promptVersion + "' requires file paths free of the characters "
                                    + "{ } \" \\ ` [ ] | * and whitespace, with no '..' segment, no leading '/', "
                                    + "and at most " + maxPathChars + " characters — use promptVersion 'v2' or "
                                    + "rename the affected file(s). If this repository uses git's default "
                                    + "core.quotePath, submit `git -c core.quotePath=false diff` instead.");
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public ReviewStatusView getStatus(Long reviewId) {
        Review review = reviewRepository.findById(reviewId).orElseThrow(() -> new ReviewNotFoundException(reviewId));
        long commentCount = reviewCommentRepository.countByReviewId(reviewId);
        return new ReviewStatusView(review.getId(), review.getStatus(), attemptsFor(reviewId),
                review.getCreatedAt(), review.getUpdatedAt(), commentCount);
    }

    /**
     * V2 (diff chunking): {@code attempts} now lives per-job, not on the parent Review (which never
     * increments its own {@code attempts} column anymore). Reports the max attempts across a Review's
     * chunk jobs — for the still-common {@code chunkCount == 1} case this is exactly that one job's
     * attempts, preserving byte-identical behavior with pre-V2 responses (§8).
     */
    private int attemptsFor(Long reviewId) {
        return reviewJobRepository.findByReviewIdOrderByChunkIndexAsc(reviewId).stream()
                .mapToInt(ReviewJob::getAttempts)
                .max()
                .orElse(0);
    }

    /**
     * Admin cancel (req. 1.4, architecture §4 rows 13-16). Idempotent in spirit: cancelling an
     * already-terminal Review is rejected as an illegal transition rather than silently succeeding.
     * CSR-17: locks the parent row first (F-DC-03: {@code FOR NO KEY UPDATE}, not {@code FOR UPDATE} —
     * see {@link ReviewRepository#findByIdForNoKeyUpdate} for why), then cascades {@code CANCELLED} to
     * every non-terminal child job (parent-then-child). CSR-19: bounded {@code lock_timeout} so a
     * contended row can never pin a Hikari connection indefinitely; a timeout surfaces as a clean
     * {@code 409 LOCK_TIMEOUT} via {@code GlobalExceptionHandler}, not a raw {@code 500} or an
     * indefinite hang.
     */
    @Transactional
    public ReviewStatusView cancel(Long reviewId) {
        applyLockTimeout();
        Review review = reviewRepository.findByIdForNoKeyUpdate(reviewId).orElseThrow(() -> new ReviewNotFoundException(reviewId));
        if (!CANCELLABLE_STATUSES.contains(review.getStatus())) {
            throw new InvalidStateTransitionException(review.getStatus(), ReviewStatus.CANCELLED);
        }
        stateMachine.transition(review, ReviewStatus.CANCELLED, EventType.CANCELLED, "cancelled by admin");
        reviewRepository.save(review);
        cascadeJobStatus(review, JobStatus.CANCELLED, EventType.CANCELLED, "review cancelled by admin");
        long commentCount = reviewCommentRepository.countByReviewId(reviewId);
        return new ReviewStatusView(review.getId(), review.getStatus(), attemptsFor(reviewId),
                review.getCreatedAt(), review.getUpdatedAt(), commentCount);
    }

    /**
     * Marks every non-terminal, non-PUBLISHED Review of this MR that has a different head_sha as
     * OBSOLETE, each through {@link StateMachine} (req. 1.5/1.11), cascading to every non-terminal
     * child job (CSR-17, parent-then-child).
     *
     * <p><b>F-DC-03 fix:</b> the candidate query itself is now a plain, unlocked read (see
     * {@link ReviewRepository#findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc}'s
     * javadoc for why {@code FOR UPDATE} there was unsafe); this method locks each candidate row
     * individually, one at a time, in that same {@code ORDER BY id} order (CSR-18 determinism
     * preserved), via {@link ReviewRepository#findByIdForNoKeyUpdate} ({@code FOR NO KEY UPDATE}, which
     * does not conflict with the child-row-insert FK trigger's {@code FOR KEY SHARE}). Each row's status
     * is re-checked against {@code OBSOLETABLE_STATUSES} immediately after it is locked, since it may
     * have changed between the unlocked candidate read and this row actually being locked.
     *
     * <p><b>F-DC-12 fix:</b> the candidate query above and the per-row lock query below run in the
     * <em>same</em> persistence-context session (both inside this one {@code REQUIRES_NEW}
     * transaction) — so when {@code findByIdForNoKeyUpdate}'s native query returns a row whose entity
     * is already managed in this session (which every candidate is, having just been loaded by the
     * query above), Hibernate hands back the <em>existing managed instance</em> rather than
     * re-hydrating its fields from the fresh result set. The {@code FOR NO KEY UPDATE} lock itself is
     * still correctly acquired at the database level, but the Java object's {@code status} field can be
     * stale relative to what that lock just protected — reproduced (appsec): a Review published
     * concurrently between the candidate read and this row's lock still read back as its old,
     * pre-publish status, letting a since-illegal {@code PUBLISHED -> OBSOLETE} transition slip past
     * the re-check above (which only ever saw the stale value) and get persisted, silently clobbering a
     * completed publish. {@link EntityManager#refresh} forces a fresh read of this row's columns from
     * the database into the (already correctly locked) managed instance before the status re-check
     * below runs. {@code ChunkCoordinator} and {@link #cancel} are not affected by this: both open a
     * dedicated {@code REQUIRES_NEW} transaction that starts with a clean, empty persistence context, so
     * there is no stale prior instance to collide with.
     *
     * <p>Runs inside {@link #requiresNewTransactionTemplate}: this method is called from
     * {@link #createReview}, which is deliberately plain/non-{@code @Transactional} (see class javadoc).
     */
    private void sweepObsolete(Long projectId, Long mergeRequestId, String newHeadSha) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            applyLockTimeout();
            List<Review> candidates = reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(
                    projectId, mergeRequestId, newHeadSha, DeduplicationService.OBSOLETABLE_STATUSES);
            for (Review candidate : candidates) {
                Review review = reviewRepository.findByIdForNoKeyUpdate(candidate.getId()).orElse(null);
                if (review != null) {
                    // F-DC-12: must run BEFORE the status re-check below -- see the method javadoc. The
                    // row is already locked by the query above; this only refreshes this Java instance's
                    // fields to match what that lock actually protects.
                    entityManager.refresh(review);
                }
                if (review == null || !DeduplicationService.OBSOLETABLE_STATUSES.contains(review.getStatus())) {
                    // Already moved on (e.g. completed/published) between the unlocked read above and
                    // this row actually being locked -- safe to skip, matches the idempotent-sweep
                    // contract.
                    continue;
                }
                stateMachine.transition(review, ReviewStatus.OBSOLETE, EventType.OBSOLETE,
                        "superseded by new head_sha for mr=" + mergeRequestId);
                reviewRepository.save(review);
                cascadeJobStatus(review, JobStatus.OBSOLETE, EventType.OBSOLETE,
                        "superseded by new head_sha for mr=" + mergeRequestId);
                log.info("Review {} marked OBSOLETE (superseded by new head_sha, mrId={})", review.getId(), mergeRequestId);
            }
        });
    }

    private void cascadeJobStatus(Review review, JobStatus target, EventType eventType, String reason) {
        List<ReviewJob> jobs = reviewJobRepository.findNonTerminalJobs(review.getId());
        for (ReviewJob job : jobs) {
            jobStateMachine.transition(job, target, eventType, job.getWorkerId(), job.getBackendId(), reason);
            reviewJobRepository.save(job);
        }
    }

    /**
     * Runs inside {@link #requiresNewTransactionTemplate}; a unique-violation surfaces on the flush
     * below. Prompt Manager (V3): writes {@code review_prompt_sections} in the same transaction as the
     * Review/chunks/jobs — a Review can never exist with chunks but no sections (architecture §3).
     */
    private Review persistNewReview(CreateReviewCommand command, DiffChunker.ChunkPlan plan,
                                     PromptManager.PromptResolution promptResolution) {
        Review review = new Review(command.projectId(), command.mergeRequestId(), command.headSha(),
                command.baseSha(), command.promptVersion(), command.priority(), promptResolution.mode());
        Review saved = reviewRepository.saveAndFlush(review);

        int estimatedTokens = diffSizeValidator.estimateTokens(command.diff());
        ReviewInput input = new ReviewInput(saved.getId(), command.diff(), command.promptVersion(),
                command.headSha(), command.baseSha(), estimatedTokens,
                promptResolution.mode() == PromptBundleMode.NONE ? null : promptResolution.estimatedTokens(),
                promptResolution.degraded());
        reviewInputRepository.save(input);

        persistPromptSections(saved.getId(), promptResolution);

        int chunkCount = plan.chunks().size();
        List<ReviewChunk> persistedChunks = new ArrayList<>();
        for (DiffChunker.DiffChunk chunk : plan.chunks()) {
            List<String> sanitizedPaths = chunk.filePaths().stream()
                    .map(chunkContextRenderer::sanitizePath)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            ReviewChunk entity = new ReviewChunk(saved.getId(), chunk.index(), chunkCount, chunk.diff(),
                    chunk.estimatedTokens(), sanitizedPaths.size(), toJsonArray(sanitizedPaths));
            persistedChunks.add(reviewChunkRepository.save(entity));
        }

        for (ReviewChunk chunkEntity : persistedChunks) {
            ReviewJob job = new ReviewJob(saved.getId(), chunkEntity.getId(), chunkEntity.getChunkIndex(),
                    command.priority(), null, null);
            reviewJobRepository.save(job);
        }

        stateMachine.transition(saved, ReviewStatus.QUEUED, EventType.CREATED,
                "project=" + command.projectId() + " mr=" + command.mergeRequestId() + " chunks=" + chunkCount);

        if (promptResolution.mode() == PromptBundleMode.NONE) {
            // PMR-10: the audit trail must positively state which reviews ran without repo-sourced rules
            // (kill-switch off), not stay silent about it.
            eventService.record(saved.getId(), EventType.PROMPT_DISABLED, null, null,
                    "gateway.prompt.enabled=false");
        }
        for (var missingKind : promptResolution.explicitPathsMissing()) {
            // PMR-11: an explicitly-configured override path that 404'd -- WARN already logged by
            // PromptManager; this is the durable review_events record of the same fact.
            eventService.record(saved.getId(), EventType.PROMPT_SECTION_MISSING, null, null,
                    "kind=" + missingKind);
        }

        return saved;
    }

    /**
     * Prompt Manager (V3): persists the resolved sections, immutable/append-only, in the same
     * transaction as the Review itself — ordinal is the assembly order {@code PromptResolution.sections()}
     * already carries.
     *
     * <p>ponytail: F-PM-11(a) (Info) -- no single assembled-output hash is recorded for the Review as a
     * whole; each row's own {@code content_sha256} (covering its exact stored bytes) is the only hash
     * persisted. Reconstructibility itself is not at risk (ordering derives from {@code kind}, the
     * preamble/trailer are compile-time constants, and every row's hash covers its exact content) — what
     * a single assembled-prompt hash would add is a forensic shortcut: an auditor could verify a
     * reconstruction without re-deriving the assembly order/framing from the code version that produced
     * it. Not implemented here because it is a genuinely new artifact (a {@code PROMPT_ASSEMBLED} event
     * or an extra column), not a fix to something already computed. Add it once a real audit/incident
     * response scenario actually needs "verify this reconstruction without cross-referencing the code" —
     * until then the per-row hashes plus the deterministic ordering already satisfy PMR-07's
     * reconstructibility requirement.
     */
    private void persistPromptSections(Long reviewId, PromptManager.PromptResolution promptResolution) {
        int ordinal = 0;
        for (PromptAssembler.AssembledSection section : promptResolution.sections()) {
            ReviewPromptSection entity = new ReviewPromptSection(reviewId, ordinal++, section.kind(),
                    section.status(), section.content(), section.sourceProject(), section.sourcePath(),
                    section.sourceRef(), section.sourceCommit(), section.contentSha256(), section.estimatedTokens());
            reviewPromptSectionRepository.save(entity);
        }
    }

    /** Minimal, dependency-free JSON string-array encoder (avoids pulling Jackson into this hot path for a tiny list). */
    private String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(values.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private CreateReviewResult toResult(Review review, boolean deduplicated) {
        int chunkCount = reviewChunkRepository.findByReviewIdOrderByChunkIndexAsc(review.getId()).size();
        return new CreateReviewResult(review.getId(), review.getStatus(), deduplicated, Math.max(1, chunkCount));
    }

    /**
     * CSR-19: bounds how long a transaction here can wait for a contended row lock, so a lock wait can
     * never pin a Hikari connection indefinitely (pool size 20). Mirrors the same helper in {@code
     * QueueManager}/{@code RetryManager}/{@code ChunkCoordinator}.
     */
    private void applyLockTimeout() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
    }
}

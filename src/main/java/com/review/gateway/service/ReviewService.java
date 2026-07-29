package com.review.gateway.service;

import com.review.gateway.exception.IncompatiblePromptVersionException;
import com.review.gateway.exception.InvalidStateTransitionException;
import com.review.gateway.exception.ReviewNotFoundException;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewChunk;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.CreateReviewCommand;
import com.review.gateway.service.dto.CreateReviewResult;
import com.review.gateway.service.dto.ReviewStatusView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
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
 * <em>parent</em> {@code reviews} row first (or, for the multi-row sweep, are returned already locked
 * by the repository query, in a deterministic {@code ORDER BY id} — CSR-18), then cascade the same
 * transition to every non-terminal child {@code review_jobs} row. This is the parent-then-child-only
 * propagation direction the fix requires; no code path here waits on a job-row lock while still
 * holding the parent lock.
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
     * (backward compatibility, §8).
     */
    private static final Set<String> CHUNK_AWARE_PROMPT_VERSIONS = Set.of("v2");

    private final ReviewRepository reviewRepository;
    private final ReviewInputRepository reviewInputRepository;
    private final ReviewChunkRepository reviewChunkRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final DeduplicationService deduplicationService;
    private final DiffSizeValidator diffSizeValidator;
    private final DiffChunker diffChunker;
    private final ChunkContextRenderer chunkContextRenderer;
    private final StateMachine stateMachine;
    private final JobStateMachine jobStateMachine;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ReviewService(ReviewRepository reviewRepository,
                          ReviewInputRepository reviewInputRepository,
                          ReviewChunkRepository reviewChunkRepository,
                          ReviewJobRepository reviewJobRepository,
                          ReviewCommentRepository reviewCommentRepository,
                          DeduplicationService deduplicationService,
                          DiffSizeValidator diffSizeValidator,
                          DiffChunker diffChunker,
                          ChunkContextRenderer chunkContextRenderer,
                          StateMachine stateMachine,
                          JobStateMachine jobStateMachine,
                          PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewInputRepository = reviewInputRepository;
        this.reviewChunkRepository = reviewChunkRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.deduplicationService = deduplicationService;
        this.diffSizeValidator = diffSizeValidator;
        this.diffChunker = diffChunker;
        this.chunkContextRenderer = chunkContextRenderer;
        this.stateMachine = stateMachine;
        this.jobStateMachine = jobStateMachine;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("ReviewService.persistNewReview");
    }

    /**
     * Creates a new Review, or returns the existing one for the same dedup key (req. 1.5). Order of
     * operations: cheap absurd-size guard (CSR-01, no DB access) -&gt; sweep prior non-terminal Reviews
     * of the MR to OBSOLETE if this head_sha is new -&gt; dedup lookup -&gt; split into chunks
     * (DiffChunker) -&gt; validate prompt-version compatibility if chunked (CSR-12) -&gt; insert
     * (racing safely against concurrent creates).
     */
    public CreateReviewResult createReview(CreateReviewCommand command) {
        diffSizeValidator.rejectIfAbsurdlyLarge(command.diff());

        sweepObsolete(command.projectId(), command.mergeRequestId(), command.headSha());

        var existing = deduplicationService.findActiveReview(
                command.projectId(), command.mergeRequestId(), command.headSha());
        if (existing.isPresent()) {
            log.info("Review create deduplicated: projectId={} mrId={} headSha={} -> existing reviewId={}",
                    command.projectId(), command.mergeRequestId(), command.headSha(), existing.get().getId());
            return toResult(existing.get(), true);
        }

        DiffChunker.ChunkPlan plan = diffChunker.split(command.diff());
        if (plan.chunks().size() > 1) {
            validatePromptVersionForChunking(command.promptVersion());
        }

        try {
            Review created = requiresNewTransactionTemplate.execute(status -> persistNewReview(command, plan));
            log.info("Review created: reviewId={} projectId={} mrId={} headSha={} chunks={}",
                    created.getId(), command.projectId(), command.mergeRequestId(), command.headSha(), plan.chunks().size());
            return toResult(created, false);
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
     * CSR-17: locks the parent row first, then cascades {@code CANCELLED} to every non-terminal child
     * job (parent-then-child).
     */
    @Transactional
    public ReviewStatusView cancel(Long reviewId) {
        Review review = reviewRepository.findByIdForUpdate(reviewId).orElseThrow(() -> new ReviewNotFoundException(reviewId));
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
     * child job (CSR-17, parent-then-child). CSR-18: the repository query already locks each candidate
     * row (in a deterministic {@code ORDER BY id}) before this loop runs.
     *
     * <p>Runs inside {@link #requiresNewTransactionTemplate}: {@code @Lock}-annotated Spring Data query
     * methods require an already-active transaction to issue {@code SELECT ... FOR UPDATE} (Spring Data
     * does not implicitly wrap locking finder methods the way it does plain reads), and this method is
     * called from {@link #createReview}, which is deliberately plain/non-{@code @Transactional} (see
     * class javadoc).
     */
    private void sweepObsolete(Long projectId, Long mergeRequestId, String newHeadSha) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            List<Review> toObsolete = reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusInOrderByIdAsc(
                    projectId, mergeRequestId, newHeadSha, DeduplicationService.OBSOLETABLE_STATUSES);
            for (Review review : toObsolete) {
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

    /** Runs inside {@link #requiresNewTransactionTemplate}; a unique-violation surfaces on the flush below. */
    private Review persistNewReview(CreateReviewCommand command, DiffChunker.ChunkPlan plan) {
        Review review = new Review(command.projectId(), command.mergeRequestId(), command.headSha(),
                command.baseSha(), command.promptVersion(), command.priority());
        Review saved = reviewRepository.saveAndFlush(review);

        int estimatedTokens = diffSizeValidator.estimateTokens(command.diff());
        ReviewInput input = new ReviewInput(saved.getId(), command.diff(), command.promptVersion(),
                command.headSha(), command.baseSha(), estimatedTokens);
        reviewInputRepository.save(input);

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
        return saved;
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
        return new CreateReviewResult(review.getId(), review.getStatus(), deduplicated);
    }
}

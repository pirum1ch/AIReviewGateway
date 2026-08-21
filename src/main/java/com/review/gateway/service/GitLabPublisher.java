package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.GitLabPublishException;
import com.review.gateway.exception.ReviewNotFoundException;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewComment;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.DiffPositionResolver.PathLine;
import com.review.gateway.service.DiffPositionResolver.ResolvedLine;
import com.review.gateway.service.dto.DiffPosition;
import com.review.gateway.service.dto.DiffRefs;
import com.review.gateway.service.dto.PublishOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Publishes a COMPLETED Review's unpublished comments to GitLab (req. 1.10). Each comment is posted
 * and marked published in its own {@code REQUIRES_NEW} transaction (via {@link TransactionTemplate} —
 * see {@code ReviewService} javadoc for why self-invoked {@code @Transactional} would not work here),
 * so a failure publishing comment N never rolls back the successful publish of comments 1..N-1
 * (idempotency, architecture §6). Only once every comment is published does the Review transition
 * {@code COMPLETED -> PUBLISHED}; a transient GitLab failure leaves it {@code COMPLETED} for
 * {@code PublishRetryService} to retry later.
 *
 * <p><b>Diff Position Anchoring:</b> {@link #buildPositionContext} resolves, once per {@link
 * #publishReview} call, a best-effort {@code (file, line) -> GitLab position} map for whichever
 * unpublished comments have both — used to anchor those comments to a native diff thread instead of a
 * top-level note. It is entirely best-effort: disabled by the {@code
 * gateway.publish.position-anchoring-enabled} flag, a stale/short {@code headSha}, an unreachable
 * GitLab, or an unresolvable diff line all fall back to today's plain-note behavior, never to a failed
 * Review. <b>DPR-01 (blocking):</b> the call is wrapped in a blanket {@code catch (RuntimeException)} —
 * this is the one new failure source this feature introduces ahead of the per-comment {@code try}, and
 * it must never be allowed to propagate (see also {@code PublishRetryService}'s own per-review guard).
 */
@Service
public class GitLabPublisher {

    private static final Logger log = LoggerFactory.getLogger(GitLabPublisher.class);

    /** DPR-06: exact-equality only, on a normalized (trimmed, lowercased) full 40-hex SHA. Never
     * startsWith/prefix matching — an abbreviated {@code review.headSha} (CI may send one;
     * {@code CreateReviewRequest.headSha} is only {@code @NotBlank}-validated) must be treated as
     * "freshness unverifiable", not as a match. */
    private static final Pattern FULL_SHA_PATTERN = Pattern.compile("^[0-9a-f]{40}$");

    private final ReviewRepository reviewRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final ReviewInputRepository reviewInputRepository;
    private final StateMachine stateMachine;
    private final GitLabClient gitLabClient;
    private final DiffPositionResolver diffPositionResolver;
    private final GatewayProperties properties;
    private final MetricsCounters metricsCounters;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public GitLabPublisher(ReviewRepository reviewRepository,
                            ReviewCommentRepository reviewCommentRepository,
                            ReviewInputRepository reviewInputRepository,
                            StateMachine stateMachine,
                            GitLabClient gitLabClient,
                            DiffPositionResolver diffPositionResolver,
                            GatewayProperties properties,
                            MetricsCounters metricsCounters,
                            PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.reviewInputRepository = reviewInputRepository;
        this.stateMachine = stateMachine;
        this.gitLabClient = gitLabClient;
        this.diffPositionResolver = diffPositionResolver;
        this.properties = properties;
        this.metricsCounters = metricsCounters;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("GitLabPublisher");
    }

    /**
     * Attempts to publish every unpublished comment of {@code reviewId}. No-op (returns
     * {@link PublishOutcome#NOT_APPLICABLE}) unless the Review is currently {@code COMPLETED} — this
     * is the OBSOLETE/CANCELLED guard from req. 1.10 (those, and every other non-COMPLETED status,
     * are simply not eligible).
     */
    @Transactional(readOnly = true)
    public PublishOutcome publishReview(Long reviewId) {
        Review review = reviewRepository.findById(reviewId).orElseThrow(() -> new ReviewNotFoundException(reviewId));
        if (review.getStatus() != ReviewStatus.COMPLETED) {
            log.debug("Skipping publish for reviewId={}: status={} (not COMPLETED)", reviewId, review.getStatus());
            return PublishOutcome.NOT_APPLICABLE;
        }

        List<ReviewComment> unpublished = reviewCommentRepository.findByReviewIdAndPublishedAtIsNull(reviewId);

        // DPR-01 (blocking): buildPositionContext is the one new failure source this feature adds ahead
        // of the per-comment try below. Position resolution touches a hand-parsed diff and a live GitLab
        // call, both MR-author/GitLab-reachable -- a single crafted hunk header must degrade this one
        // Review to plain notes, never abort the whole publish pass. Class name only, never
        // e.getMessage() (WOR-05/F02-03: a parse exception can quote diff text).
        PositionContext positionContext;
        try {
            positionContext = buildPositionContext(review, unpublished);
        } catch (RuntimeException e) {
            log.warn("position context unavailable: {}", e.getClass().getSimpleName());
            positionContext = null;
        }

        boolean anyFailure = false;
        for (ReviewComment comment : unpublished) {
            DiffPosition position = resolvePositionFor(positionContext, comment);
            try {
                publishOneComment(review.getProjectId(), review.getMergeRequestId(), comment.getId(), position);
            } catch (GitLabPublishException transientFailure) {
                anyFailure = true;
                log.warn("Transient GitLab publish failure for reviewId={} commentId={}: {}",
                        reviewId, comment.getId(), transientFailure.getMessage());
            }
        }

        if (anyFailure) {
            return PublishOutcome.PARTIAL;
        }

        boolean finalized = requiresNewTransactionTemplate.execute(status -> finalizePublished(reviewId));
        return finalized ? PublishOutcome.PUBLISHED : PublishOutcome.PARTIAL;
    }

    /**
     * Diff Position Anchoring: resolves diff positions for whichever of {@code unpublished} have both a
     * {@code filePath} and a {@code lineNumber}. Returns {@code null} (best-effort no-op, plain notes)
     * whenever anchoring cannot proceed — never throws (that guarantee is enforced by the caller's
     * {@code catch (RuntimeException)}, not repeated here as a second safety net).
     *
     * <p>DPR-11: {@code fetchDiffRefs} is skipped entirely (zero GitLab calls) when the flag is off, when
     * there are no unpublished comments, or when none has both a file and a line — the three cheap checks
     * below all run before any I/O.
     */
    private PositionContext buildPositionContext(Review review, List<ReviewComment> unpublished) {
        if (!properties.getPublish().isPositionAnchoringEnabled()) {
            return null;
        }
        Set<PathLine> wanted = collectWantedKeys(unpublished);
        if (wanted.isEmpty()) {
            return null;
        }

        String normalizedReviewHeadSha = normalizeFullSha(review.getHeadSha());
        if (normalizedReviewHeadSha == null) {
            // DPR-06: an abbreviated/malformed review.headSha means freshness can never be verified --
            // never fall back to prefix matching, just skip positioning for this Review.
            log.debug("Skipping position anchoring for reviewId={}: review.headSha is not a well-formed "
                    + "40-hex SHA (freshness unverifiable)", review.getId());
            return null;
        }

        Optional<DiffRefs> diffRefs = gitLabClient.fetchDiffRefs(review.getProjectId(), review.getMergeRequestId());
        if (diffRefs.isEmpty()) {
            metricsCounters.incrementDiffRefsUnavailable();
            return null;
        }
        DiffRefs refs = diffRefs.get();
        if (!normalizedReviewHeadSha.equals(normalizeFullSha(refs.headSha()))) {
            log.debug("Skipping position anchoring for reviewId={}: diff_refs.head_sha does not match "
                    + "review.headSha (stale MR state)", review.getId());
            return null;
        }

        Optional<ReviewInput> reviewInput = reviewInputRepository.findByReviewId(review.getId());
        if (reviewInput.isEmpty() || reviewInput.get().getDiff() == null) {
            return null;
        }

        Map<PathLine, ResolvedLine> resolved = diffPositionResolver.resolve(reviewInput.get().getDiff(), wanted);
        if (resolved.isEmpty()) {
            return null;
        }
        return new PositionContext(refs, resolved);
    }

    private Set<PathLine> collectWantedKeys(List<ReviewComment> unpublished) {
        Set<PathLine> keys = new LinkedHashSet<>();
        for (ReviewComment comment : unpublished) {
            if (comment.getFilePath() != null && comment.getLineNumber() != null) {
                // DPR-05: HtmlUtils.htmlUnescape exactly once, to rebuild the key CommentParser's
                // htmlEscape produced -- never touched again (a second unescape pass would turn e.g.
                // "&amp;#10;" into an actual newline).
                keys.add(new PathLine(HtmlUtils.htmlUnescape(comment.getFilePath()), comment.getLineNumber()));
            }
        }
        return keys;
    }

    private DiffPosition resolvePositionFor(PositionContext positionContext, ReviewComment comment) {
        if (positionContext == null || comment.getFilePath() == null || comment.getLineNumber() == null) {
            return null;
        }
        PathLine key = new PathLine(HtmlUtils.htmlUnescape(comment.getFilePath()), comment.getLineNumber());
        ResolvedLine resolved = positionContext.resolved().get(key);
        if (resolved == null) {
            metricsCounters.incrementPositionsUnresolved();
            return null;
        }
        metricsCounters.incrementPositionsAnchored();
        DiffRefs refs = positionContext.diffRefs();
        return new DiffPosition(refs.baseSha(), refs.startSha(), refs.headSha(),
                resolved.oldPath(), resolved.newPath(), resolved.oldLine(), resolved.newLine());
    }

    /** DPR-06: trim + lowercase, then a full-40-hex-only check -- never a prefix/startsWith comparison. */
    private String normalizeFullSha(String rawSha) {
        if (rawSha == null) {
            return null;
        }
        String normalized = rawSha.trim().toLowerCase(Locale.ROOT);
        return FULL_SHA_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    private void publishOneComment(Long projectId, Long mergeRequestId, Long commentId, DiffPosition position) {
        requiresNewTransactionTemplate.executeWithoutResult(status -> {
            ReviewComment fresh = reviewCommentRepository.findById(commentId).orElse(null);
            if (fresh == null || fresh.getPublishedAt() != null) {
                return; // already published concurrently (e.g. a racing retry sweep) - idempotent skip
            }
            String discussionId = gitLabClient.postDiscussion(projectId, mergeRequestId, fresh.getComment(), position);
            fresh.setDiscussionId(discussionId);
            fresh.setPublishedAt(Instant.now());
            reviewCommentRepository.save(fresh);
        });
    }

    /**
     * Re-checks the Review is still COMPLETED (it may have raced to OBSOLETE/CANCELLED while
     * comments were being posted) before flipping it to PUBLISHED, and re-checks that no comment is
     * still unpublished (a concurrent publish attempt, or a comment created after the initial fetch).
     *
     * @return whether the Review actually transitioned to PUBLISHED
     */
    private boolean finalizePublished(Long reviewId) {
        Review fresh = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalStateException("Review " + reviewId + " vanished during publish finalization"));
        if (fresh.getStatus() != ReviewStatus.COMPLETED) {
            log.debug("Not finalizing publish for reviewId={}: status changed to {} mid-publish", reviewId, fresh.getStatus());
            return false;
        }
        long stillUnpublished = reviewCommentRepository.findByReviewIdAndPublishedAtIsNull(reviewId).size();
        if (stillUnpublished > 0) {
            log.debug("Not finalizing publish for reviewId={}: {} comment(s) still unpublished", reviewId, stillUnpublished);
            return false;
        }
        stateMachine.transition(fresh, ReviewStatus.PUBLISHED, EventType.PUBLISHED, "all comments published");
        reviewRepository.save(fresh);
        return true;
    }

    /** Diff Position Anchoring: the per-{@code publishReview}-call resolved position index, plus the
     * {@link DiffRefs} every {@link DiffPosition} built from it shares. */
    private record PositionContext(DiffRefs diffRefs, Map<PathLine, ResolvedLine> resolved) {
    }
}

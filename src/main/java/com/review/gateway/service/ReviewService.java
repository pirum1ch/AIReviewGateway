package com.review.gateway.service;

import com.review.gateway.exception.InvalidStateTransitionException;
import com.review.gateway.exception.ReviewNotFoundException;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewInputRepository;
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

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates Review creation, status reads, and admin cancellation (architecture §2). Creation is
 * deliberately split across independent transactions rather than one big one:
 * <ol>
 *   <li>the OBSOLETE sweep and the dedup read are each a normal repository call (its own default
 *       transaction);</li>
 *   <li>the actual insert of the new {@code reviews}/{@code review_inputs} rows runs in its own
 *       {@code REQUIRES_NEW} transaction via {@link #requiresNewTransactionTemplate}, so that a
 *       unique-violation race (two concurrent creates for the same dedup key) only rolls back that
 *       small insert attempt, never the sweep that already committed.</li>
 * </ol>
 * {@link TransactionTemplate} (not a self-invoked {@code @Transactional} private method) is used
 * deliberately: Spring's AOP transaction advice does not apply to same-class method calls, so a
 * {@code this.insert(...)} call would silently run in the caller's transaction instead of a new one.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private static final Set<ReviewStatus> CANCELLABLE_STATUSES = EnumSet.of(
            ReviewStatus.NEW, ReviewStatus.QUEUED, ReviewStatus.RUNNING, ReviewStatus.COMPLETED);

    private final ReviewRepository reviewRepository;
    private final ReviewInputRepository reviewInputRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final DeduplicationService deduplicationService;
    private final DiffSizeValidator diffSizeValidator;
    private final StateMachine stateMachine;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ReviewService(ReviewRepository reviewRepository,
                          ReviewInputRepository reviewInputRepository,
                          ReviewCommentRepository reviewCommentRepository,
                          DeduplicationService deduplicationService,
                          DiffSizeValidator diffSizeValidator,
                          StateMachine stateMachine,
                          PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewInputRepository = reviewInputRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.deduplicationService = deduplicationService;
        this.diffSizeValidator = diffSizeValidator;
        this.stateMachine = stateMachine;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("ReviewService.persistNewReview");
    }

    /**
     * Creates a new Review, or returns the existing one for the same dedup key (req. 1.5). Order of
     * operations: validate diff size (no DB access, cheapest possible fail-fast) -&gt; sweep prior
     * non-terminal Reviews of the MR to OBSOLETE if this head_sha is new -&gt; dedup lookup -&gt;
     * insert (racing safely against concurrent creates).
     */
    public CreateReviewResult createReview(CreateReviewCommand command) {
        diffSizeValidator.validate(command.diff());

        sweepObsolete(command.projectId(), command.mergeRequestId(), command.headSha());

        var existing = deduplicationService.findActiveReview(
                command.projectId(), command.mergeRequestId(), command.headSha());
        if (existing.isPresent()) {
            log.info("Review create deduplicated: projectId={} mrId={} headSha={} -> existing reviewId={}",
                    command.projectId(), command.mergeRequestId(), command.headSha(), existing.get().getId());
            return toResult(existing.get(), true);
        }

        try {
            Review created = requiresNewTransactionTemplate.execute(status -> persistNewReview(command));
            log.info("Review created: reviewId={} projectId={} mrId={} headSha={}",
                    created.getId(), command.projectId(), command.mergeRequestId(), command.headSha());
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

    @Transactional(readOnly = true)
    public ReviewStatusView getStatus(Long reviewId) {
        Review review = reviewRepository.findById(reviewId).orElseThrow(() -> new ReviewNotFoundException(reviewId));
        long commentCount = reviewCommentRepository.countByReviewId(reviewId);
        return new ReviewStatusView(review.getId(), review.getStatus(), review.getAttempts(),
                review.getCreatedAt(), review.getUpdatedAt(), commentCount);
    }

    /**
     * Admin cancel (req. 1.4, architecture §4 rows 13-16). Idempotent in spirit: cancelling an
     * already-terminal Review is rejected as an illegal transition rather than silently succeeding,
     * so a caller can distinguish "already cancelled/finished" from "cancel accepted".
     */
    @Transactional
    public ReviewStatusView cancel(Long reviewId) {
        Review review = reviewRepository.findById(reviewId).orElseThrow(() -> new ReviewNotFoundException(reviewId));
        if (!CANCELLABLE_STATUSES.contains(review.getStatus())) {
            throw new InvalidStateTransitionException(review.getStatus(), ReviewStatus.CANCELLED);
        }
        stateMachine.transition(review, ReviewStatus.CANCELLED, EventType.CANCELLED, "cancelled by admin");
        reviewRepository.save(review);
        long commentCount = reviewCommentRepository.countByReviewId(reviewId);
        return new ReviewStatusView(review.getId(), review.getStatus(), review.getAttempts(),
                review.getCreatedAt(), review.getUpdatedAt(), commentCount);
    }

    /**
     * Marks every non-terminal, non-PUBLISHED Review of this MR that has a different head_sha as
     * OBSOLETE, each through {@link StateMachine} so every affected Review gets its own audit event
     * (req. 1.5/1.11). Safe to repeat: rows already moved on are simply not returned by the query.
     */
    private void sweepObsolete(Long projectId, Long mergeRequestId, String newHeadSha) {
        List<Review> toObsolete = reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaNotAndStatusIn(
                projectId, mergeRequestId, newHeadSha, DeduplicationService.OBSOLETABLE_STATUSES);
        for (Review review : toObsolete) {
            stateMachine.transition(review, ReviewStatus.OBSOLETE, EventType.OBSOLETE,
                    "superseded by new head_sha for mr=" + mergeRequestId);
            reviewRepository.save(review);
            log.info("Review {} marked OBSOLETE (superseded by new head_sha, mrId={})", review.getId(), mergeRequestId);
        }
    }

    /** Runs inside {@link #requiresNewTransactionTemplate}; a unique-violation surfaces on the flush below. */
    private Review persistNewReview(CreateReviewCommand command) {
        Review review = new Review(command.projectId(), command.mergeRequestId(), command.headSha(),
                command.baseSha(), command.promptVersion(), command.priority());
        Review saved = reviewRepository.saveAndFlush(review);

        int estimatedTokens = diffSizeValidator.estimateTokens(command.diff());
        ReviewInput input = new ReviewInput(saved.getId(), command.diff(), command.promptVersion(),
                command.headSha(), command.baseSha(), estimatedTokens);
        reviewInputRepository.save(input);

        stateMachine.transition(saved, ReviewStatus.QUEUED, EventType.CREATED,
                "project=" + command.projectId() + " mr=" + command.mergeRequestId());
        return saved;
    }

    private CreateReviewResult toResult(Review review, boolean deduplicated) {
        return new CreateReviewResult(review.getId(), review.getStatus(), deduplicated);
    }
}

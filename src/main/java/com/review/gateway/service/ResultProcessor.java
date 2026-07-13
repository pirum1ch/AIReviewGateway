package com.review.gateway.service;

import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewComment;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.ReviewResult;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.repository.ReviewResultRepository;
import com.review.gateway.service.dto.ParsedComment;
import com.review.gateway.service.dto.SubmitResultCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Handles the "job finished, now what" half of req. 1.9: stores the raw model response first, in its
 * own committed transaction, then parses it and either completes or fails the Review. Each phase is
 * its own {@code REQUIRES_NEW} transaction (via {@link TransactionTemplate} — see {@code ReviewService}
 * javadoc for why a self-invoked {@code @Transactional} method would not work here) so that a crash or
 * exception during parsing can never lose the already-stored raw response.
 */
@Service
public class ResultProcessor {

    private static final Logger log = LoggerFactory.getLogger(ResultProcessor.class);

    private final ReviewRepository reviewRepository;
    private final ReviewJobRepository reviewJobRepository;
    private final ReviewResultRepository reviewResultRepository;
    private final ReviewCommentRepository reviewCommentRepository;
    private final CommentParser commentParser;
    private final StateMachine stateMachine;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ResultProcessor(ReviewRepository reviewRepository,
                            ReviewJobRepository reviewJobRepository,
                            ReviewResultRepository reviewResultRepository,
                            ReviewCommentRepository reviewCommentRepository,
                            CommentParser commentParser,
                            StateMachine stateMachine,
                            PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.reviewResultRepository = reviewResultRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.commentParser = commentParser;
        this.stateMachine = stateMachine;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("ResultProcessor");
    }

    /**
     * @return the Review's final status after processing ({@code COMPLETED} or {@code FAILED})
     */
    public ReviewStatus process(Long reviewId, Long jobId, String workerId, Long backendId, SubmitResultCommand command) {
        requiresNewTransactionTemplate.executeWithoutResult(status ->
                storeRawResult(reviewId, backendId, command));

        List<ParsedComment> parsed;
        try {
            parsed = commentParser.parse(command.rawResponse());
        } catch (RuntimeException parseError) {
            log.warn("Comment parsing failed for reviewId={}: {}", reviewId, parseError.toString());
            requiresNewTransactionTemplate.executeWithoutResult(status ->
                    markFailed(reviewId, jobId, workerId, backendId, parseError));
            return ReviewStatus.FAILED;
        }

        requiresNewTransactionTemplate.executeWithoutResult(status ->
                persistCommentsAndComplete(reviewId, jobId, workerId, backendId, parsed));
        return ReviewStatus.COMPLETED;
    }

    /** Idempotent: a pre-existing row (e.g. a retried delivery after a mid-process crash) is left untouched. */
    private void storeRawResult(Long reviewId, Long backendId, SubmitResultCommand command) {
        if (reviewResultRepository.existsByReviewId(reviewId)) {
            log.debug("review_results already present for reviewId={}, skipping insert", reviewId);
            return;
        }
        Integer totalTokens = (command.promptTokens() != null && command.completionTokens() != null)
                ? command.promptTokens() + command.completionTokens()
                : null;
        ReviewResult result = new ReviewResult(reviewId, command.rawResponse(), null,
                command.promptTokens(), command.completionTokens(), totalTokens,
                command.durationMs(), command.model(), backendId);
        try {
            reviewResultRepository.save(result);
        } catch (DataIntegrityViolationException alreadyStored) {
            log.debug("Concurrent review_results insert for reviewId={}, ignoring", reviewId);
        }
    }

    private void markFailed(Long reviewId, Long jobId, String workerId, Long backendId, Exception cause) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalStateException("Review " + reviewId + " vanished during result processing"));
        if (review.getStatus() != ReviewStatus.RUNNING) {
            log.debug("Review {} no longer RUNNING ({}) when marking parse-failure FAILED, skipping", reviewId, review.getStatus());
            return;
        }
        stateMachine.transition(review, ReviewStatus.FAILED, EventType.FAILED, workerId, backendId,
                "parse error: " + cause.getClass().getSimpleName());
        reviewRepository.save(review);
        finishJob(jobId);
    }

    private void persistCommentsAndComplete(Long reviewId, Long jobId, String workerId, Long backendId, List<ParsedComment> parsed) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalStateException("Review " + reviewId + " vanished during result processing"));
        if (review.getStatus() != ReviewStatus.RUNNING) {
            log.debug("Review {} no longer RUNNING ({}) when completing, skipping", reviewId, review.getStatus());
            return;
        }
        for (ParsedComment comment : parsed) {
            reviewCommentRepository.save(new ReviewComment(reviewId, comment.filePath(), comment.lineNumber(),
                    comment.severity(), comment.text()));
        }
        stateMachine.transition(review, ReviewStatus.COMPLETED, EventType.COMPLETED, workerId, backendId,
                "comments=" + parsed.size());
        reviewRepository.save(review);
        finishJob(jobId);
    }

    private void finishJob(Long jobId) {
        reviewJobRepository.findById(jobId).ifPresent(job -> {
            job.setFinishedAt(Instant.now());
            reviewJobRepository.save(job);
        });
    }
}

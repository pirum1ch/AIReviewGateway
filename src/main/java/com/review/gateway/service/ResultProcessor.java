package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
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
    private final GatewayProperties properties;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ResultProcessor(ReviewRepository reviewRepository,
                            ReviewJobRepository reviewJobRepository,
                            ReviewResultRepository reviewResultRepository,
                            ReviewCommentRepository reviewCommentRepository,
                            CommentParser commentParser,
                            StateMachine stateMachine,
                            GatewayProperties properties,
                            PlatformTransactionManager transactionManager) {
        this.reviewRepository = reviewRepository;
        this.reviewJobRepository = reviewJobRepository;
        this.reviewResultRepository = reviewResultRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.commentParser = commentParser;
        this.stateMachine = stateMachine;
        this.properties = properties;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("ResultProcessor");
    }

    /**
     * @return the Review's final status after processing ({@code COMPLETED} or {@code FAILED})
     */
    public ReviewStatus process(Long reviewId, Long jobId, String workerId, Long backendId, SubmitResultCommand command) {
        // F02-01/SR-21: cap the raw response BEFORE it is persisted and BEFORE it is handed to
        // CommentParser (which otherwise runs indexOf/lastIndexOf/substring + a full JSON parse over
        // the whole blob) -- an oversized response (compromised worker, or a prompt-injected model,
        // T-19) would otherwise cause unbounded storage growth and CPU/heap pressure on the single
        // Gateway (SPOF). The architecture doc is silent on reject-vs-truncate for this cap, so we
        // truncate with a marker and let the Review continue processing normally (COMPLETED/FAILED as
        // the truncated content dictates) rather than failing an otherwise-usable result outright.
        CappedRawResponse capped = capRawResponseIfNeeded(command.rawResponse());
        SubmitResultCommand effectiveCommand = capped.truncated()
                ? new SubmitResultCommand(capped.value(), command.promptTokens(), command.completionTokens(),
                        command.durationMs(), command.model())
                : command;

        requiresNewTransactionTemplate.executeWithoutResult(status ->
                storeRawResult(reviewId, backendId, effectiveCommand));

        List<ParsedComment> parsed;
        try {
            parsed = commentParser.parse(effectiveCommand.rawResponse());
        } catch (RuntimeException parseError) {
            // F02-03/SR-14: log only the exception class, never parseError.toString() -- the message
            // of a parse failure over untrusted input can itself echo a fragment of raw_response.
            log.warn("Comment parsing failed for reviewId={}: {}", reviewId, parseError.getClass().getSimpleName());
            requiresNewTransactionTemplate.executeWithoutResult(status ->
                    markFailed(reviewId, jobId, workerId, backendId, parseError, capped));
            return ReviewStatus.FAILED;
        }

        requiresNewTransactionTemplate.executeWithoutResult(status ->
                persistCommentsAndComplete(reviewId, jobId, workerId, backendId, parsed, capped));
        return ReviewStatus.COMPLETED;
    }

    /**
     * F02-01/SR-21: truncates {@code rawResponse} to {@code gateway.publish.max-raw-response-length}
     * (already configured, previously unused anywhere — SAST F02-01) if it exceeds the cap, appending a
     * clearly-identifiable marker. Never rejects: an oversized-but-otherwise-valid result should still
     * complete the Review rather than burning a retry attempt.
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

    private void markFailed(Long reviewId, Long jobId, String workerId, Long backendId, Exception cause, CappedRawResponse capped) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalStateException("Review " + reviewId + " vanished during result processing"));
        if (review.getStatus() != ReviewStatus.RUNNING) {
            log.debug("Review {} no longer RUNNING ({}) when marking parse-failure FAILED, skipping", reviewId, review.getStatus());
            return;
        }
        stateMachine.transition(review, ReviewStatus.FAILED, EventType.FAILED, workerId, backendId,
                "parse error: " + cause.getClass().getSimpleName() + capped.auditNote());
        reviewRepository.save(review);
        finishJob(jobId);
    }

    private void persistCommentsAndComplete(Long reviewId, Long jobId, String workerId, Long backendId,
                                             List<ParsedComment> parsed, CappedRawResponse capped) {
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
                "comments=" + parsed.size() + capped.auditNote());
        reviewRepository.save(review);
        finishJob(jobId);
    }

    /**
     * F02-01/SR-21: carries whether {@link #capRawResponseIfNeeded} truncated the raw response, so the
     * fact (never the content — SR-14) can be recorded in the audit trail. The frozen V1 {@code
     * EventType} enum has no dedicated "truncated" event type, so the note is appended to whichever
     * transition event actually fires ({@code COMPLETED}/{@code FAILED}) rather than inventing a new
     * event write path.
     */
    private record CappedRawResponse(String value, boolean truncated, int originalLength, int limit) {
        String auditNote() {
            return truncated ? ("; raw_response truncated " + originalLength + "->" + limit + " chars") : "";
        }
    }

    private void finishJob(Long jobId) {
        reviewJobRepository.findById(jobId).ifPresent(job -> {
            job.setFinishedAt(Instant.now());
            reviewJobRepository.save(job);
        });
    }
}

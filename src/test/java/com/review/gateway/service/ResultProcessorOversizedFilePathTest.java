package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewResultRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.SubmitResultCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gap-fill for {@code CommentParserTest}: SR-08/SR-09 require caps on the parsed comment <em>text</em>
 * (already covered — {@code commentLengthIsCappedWithTruncationMarker}), but {@link CommentParser}
 * applies no equivalent cap to the LLM-controlled {@code filePath} field
 * ({@code CommentParser.sanitize(...)} passes {@code candidate.filePath()} straight through). The
 * {@code review_comments.file_path} column is {@code VARCHAR(1024)} ({@code ReviewComment.java},
 * V1 migration), so an LLM response (or a diff engineered to elicit one, T-06/T-19) with a
 * {@code "file"} value longer than 1024 characters reaches {@link ResultProcessor#process} and fails
 * at the database layer instead of being truncated/capped like the comment text is.
 *
 * <p><b>DEFECT (Important):</b> confirmed against a real Postgres instance below: the oversized
 * {@code file_path} causes {@code persistCommentsAndComplete}'s {@code REQUIRES_NEW} transaction to
 * roll back with a {@code DataIntegrityViolationException}, which propagates uncaught out of
 * {@link ResultProcessor#process} (no try/catch around that phase, unlike the parse-failure phase) —
 * so the Review is left permanently stuck {@code RUNNING} (neither {@code COMPLETED} nor
 * {@code FAILED}), and the Worker's {@code POST /jobs/{id}/result} call fails with an unhandled
 * exception instead of a clean {@code FAILED} outcome. Suggested fix: {@code CommentParser.sanitize}
 * should cap {@code filePath} length (e.g. to 1024, matching the column) the same way it already caps
 * comment text length, dropping/truncating rather than passing oversized values through.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResultProcessorOversizedFilePathTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewResultRepository reviewResultRepository;
    @Autowired
    private ReviewCommentRepository reviewCommentRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUpCommittedRows() {
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private ResultProcessor newResultProcessor(CommentParser commentParser) {
        EventService eventService = new EventService(reviewEventRepository);
        StateMachine stateMachine = new StateMachine(eventService);
        return new ResultProcessor(reviewRepository, reviewJobRepository, reviewResultRepository,
                reviewCommentRepository, commentParser, stateMachine, transactionManager);
    }

    private Review persistRunningReview(String headSha) {
        Review review = new Review(1L, 1L, headSha, "base", "v1", 10);
        review.setStatus(ReviewStatus.RUNNING);
        review.setAttempts(1);
        return reviewRepository.saveAndFlush(review);
    }

    private ReviewJob persistJob(Review review) {
        Backend backend = backendRepository.saveAndFlush(
                new Backend("backend-fp-" + review.getId(), "https://backend-fp.local", "model", 1));
        ReviewJob job = new ReviewJob(review.getId(), backend.getId(), "worker-1");
        job.setStartedAt(Instant.now());
        return reviewJobRepository.saveAndFlush(job);
    }

    @Test
    void oversizedFilePathCrashesPersistenceInsteadOfBeingCappedLikeCommentTextIs() {
        Review review = persistRunningReview("sha-oversized-filepath");
        ReviewJob job = persistJob(review);

        CommentParser commentParser = new CommentParser(new GatewayProperties());
        ResultProcessor processor = newResultProcessor(commentParser);

        String hugeFilePath = "a/".repeat(1000) + "File.java"; // ~3000 chars, column is VARCHAR(1024)
        String raw = "[{\"file\":\"" + hugeFilePath + "\",\"line\":1,\"severity\":\"MINOR\",\"comment\":\"finding\"}]";

        assertThatThrownBy(() -> processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(raw, 10, 5, 1000L, "model-x")))
                .as("DEFECT: CommentParser does not cap filePath length the way it caps comment text, "
                        + "so an oversized LLM-supplied file path overflows review_comments.file_path VARCHAR(1024) "
                        + "and crashes persistence instead of being truncated")
                .isInstanceOf(DataIntegrityViolationException.class);

        // The raw response is still safely durable (req. 1.9's other guarantee holds independently)...
        assertThat(reviewResultRepository.existsByReviewId(review.getId())).isTrue();
        // ...but the Review itself is left stuck RUNNING: neither COMPLETED nor FAILED, because the
        // exception escaped process() before either transition could run.
        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.RUNNING);
    }
}

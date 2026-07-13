package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewComment;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Gap-fill for {@code CommentParserTest}: SR-08/SR-09 require caps on the parsed comment <em>text</em>
 * (already covered — {@code commentLengthIsCappedWithTruncationMarker}), and {@link CommentParser} now
 * applies the equivalent cap to the LLM-controlled {@code filePath} field too
 * ({@code CommentParser.sanitize(...)} routes {@code candidate.filePath()} through
 * {@code sanitizeFilePath}: newline collapse, length cap to {@code review_comments.file_path
 * VARCHAR(1024)}, mention-neutralization, HTML-escape — F02-04/KD-2).
 *
 * <p><b>Fixed (previously DEFECT, Important):</b> before this fix, an oversized {@code file_path} made
 * {@code persistCommentsAndComplete}'s {@code REQUIRES_NEW} transaction roll back with a
 * {@code DataIntegrityViolationException} that propagated uncaught out of
 * {@link ResultProcessor#process}, permanently wedging the Review in {@code RUNNING}. Now the oversized
 * path is capped to 1024 chars (with a truncation marker) before the comment is ever persisted, so the
 * same result submission completes normally.
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
                reviewCommentRepository, commentParser, stateMachine, new GatewayProperties(), transactionManager);
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
    void oversizedFilePathIsCappedInsteadOfCrashingPersistence() {
        Review review = persistRunningReview("sha-oversized-filepath");
        ReviewJob job = persistJob(review);

        CommentParser commentParser = new CommentParser(new GatewayProperties());
        ResultProcessor processor = newResultProcessor(commentParser);

        String hugeFilePath = "a/".repeat(1000) + "File.java"; // ~3000 chars, column is VARCHAR(1024)
        String raw = "[{\"file\":\"" + hugeFilePath + "\",\"line\":1,\"severity\":\"MINOR\",\"comment\":\"finding\"}]";

        assertThatCode(() -> processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(raw, 10, 5, 1000L, "model-x")))
                .as("FIXED: CommentParser now caps filePath length the same way it caps comment text, "
                        + "so an oversized LLM-supplied file path no longer overflows "
                        + "review_comments.file_path VARCHAR(1024)")
                .doesNotThrowAnyException();

        assertThat(reviewResultRepository.existsByReviewId(review.getId())).isTrue();

        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.COMPLETED);

        List<ReviewComment> comments = reviewCommentRepository.findByReviewId(review.getId());
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).getFilePath()).hasSizeLessThanOrEqualTo(1024);
        assertThat(comments.get(0).getFilePath()).endsWith("[truncated]");
    }

    /**
     * F02-08 regression: a {@code filePath} that is entirely HTML-special characters is short enough
     * pre-escape ({@code <= 1024}) but balloons well past the column limit once escaped
     * ({@code "} -> {@code &quot;} is a 6x inflation) -- this must still be capped to {@code <=1024}
     * (capped AFTER escaping) and the Review must still complete normally, not crash persistence.
     */
    @Test
    void filePathOfAllQuoteCharactersIsCappedAfterEscapingAndReviewCompletesNormally() {
        Review review = persistRunningReview("sha-escaped-filepath-inflation");
        ReviewJob job = persistJob(review);

        CommentParser commentParser = new CommentParser(new GatewayProperties());
        ResultProcessor processor = newResultProcessor(commentParser);

        // 1024 literal '"' characters pre-escape -- exactly at the column limit before escaping, but
        // 1024 * 6 = 6144 chars once HtmlUtils.htmlEscape turns each '"' into "&quot;" (the F02-08 defect).
        String rawFilePathValue = "\"".repeat(1024);
        String jsonEscapedFilePathValue = rawFilePathValue.replace("\"", "\\\"");
        String raw = "[{\"file\":\"" + jsonEscapedFilePathValue + "\",\"comment\":\"finding\"}]";

        assertThatCode(() -> processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(raw, 10, 5, 1000L, "model-x")))
                .as("FIXED: capLength runs after htmlEscape, so escaping's inflation of an all-quote "
                        + "filePath cannot overflow review_comments.file_path VARCHAR(1024)")
                .doesNotThrowAnyException();

        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.COMPLETED);

        List<ReviewComment> comments = reviewCommentRepository.findByReviewId(review.getId());
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).getFilePath()).hasSizeLessThanOrEqualTo(1024);
        assertThat(comments.get(0).getFilePath()).endsWith("[truncated]");
    }
}

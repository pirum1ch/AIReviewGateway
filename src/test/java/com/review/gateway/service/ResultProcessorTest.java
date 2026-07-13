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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link ResultProcessor}'s two-phase durability guarantee against a real (Zonky) database:
 * the raw response is stored in its own committed transaction before parsing is ever attempted, so it
 * survives a parse failure (req. 1.9). {@link CommentParser} is mocked in the failure-path test so
 * parse failure can be deterministically simulated; its own parsing behavior is covered by
 * {@code CommentParserTest}.
 *
 * <p>{@code @Transactional(propagation = NOT_SUPPORTED)} disables {@code @DataJpaTest}'s default
 * per-test-method transaction wrapper. {@link ResultProcessor} deliberately opens its own
 * {@code REQUIRES_NEW} transactions (via {@code TransactionTemplate}), which run as physically
 * separate transactions from whatever called them. If setup data were only flushed inside the
 * ambient per-test transaction (as {@code @DataJpaTest} does by default, only rolled back at the very
 * end of the test method), it would still be uncommitted and therefore invisible under read-committed
 * isolation to those separate {@code REQUIRES_NEW} transactions — causing exactly the spurious
 * foreign-key failures this fix addresses. Using the repositories directly (instead of
 * {@code TestEntityManager}, whose {@code persistAndFlush} only flushes within whatever transaction
 * happens to be active) for setup ensures every fixture row is genuinely committed before
 * {@link ResultProcessor#process} is invoked, exactly like production request handling.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResultProcessorTest extends AbstractPostgresIntegrationTest {

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

    /**
     * With {@code @Transactional(NOT_SUPPORTED)} disabling {@code @DataJpaTest}'s default per-test
     * rollback, every row this test commits would otherwise persist in the (test-context-cached,
     * shared-across-test-classes) embedded database indefinitely, polluting other tests' unscoped
     * queries (e.g. {@code ReviewRepositoryTest}'s exact-count/exact-list assertions over ALL rows).
     * Explicit cleanup restores the DB to the state other test classes expect. {@code reviews} cascades
     * to {@code review_inputs}/{@code review_jobs}/{@code review_results}/{@code review_comments}/
     * {@code review_events} at the DB level (V1 migration, {@code ON DELETE CASCADE}).
     */
    @AfterEach
    void cleanUpCommittedRows() {
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private ResultProcessor newResultProcessor(CommentParser commentParser) {
        return newResultProcessor(commentParser, new GatewayProperties());
    }

    private ResultProcessor newResultProcessor(CommentParser commentParser, GatewayProperties properties) {
        EventService eventService = new EventService(reviewEventRepository);
        StateMachine stateMachine = new StateMachine(eventService);
        return new ResultProcessor(reviewRepository, reviewJobRepository, reviewResultRepository,
                reviewCommentRepository, commentParser, stateMachine, properties, transactionManager);
    }

    private Review persistRunningReview(String headSha) {
        Review review = new Review(1L, 1L, headSha, "base", "v1", 10);
        review.setStatus(ReviewStatus.RUNNING);
        review.setAttempts(1);
        return reviewRepository.saveAndFlush(review);
    }

    private ReviewJob persistJob(Review review) {
        Backend backend = backendRepository.saveAndFlush(
                new Backend("backend-rp-" + review.getId(), "https://backend-rp.local", "model", 1));
        ReviewJob job = new ReviewJob(review.getId(), backend.getId(), "worker-1");
        job.setStartedAt(Instant.now());
        return reviewJobRepository.saveAndFlush(job);
    }

    @Test
    void rawResponseIsStoredEvenWhenParsingFails() {
        Review review = persistRunningReview("sha-parse-fail");
        ReviewJob job = persistJob(review);

        CommentParser commentParser = Mockito.mock(CommentParser.class);
        when(commentParser.parse("raw-broken")).thenThrow(new RuntimeException("boom"));

        ResultProcessor processor = newResultProcessor(commentParser);
        ReviewStatus finalStatus = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("raw-broken", 10, 5, 1000L, "model-x"));

        assertThat(finalStatus).isEqualTo(ReviewStatus.FAILED);
        assertThat(reviewResultRepository.existsByReviewId(review.getId())).isTrue();
        assertThat(reviewResultRepository.findByReviewId(review.getId()).orElseThrow().getRawResponse())
                .isEqualTo("raw-broken");
        assertThat(reviewCommentRepository.findByReviewId(review.getId())).isEmpty();

        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.FAILED);
    }

    @Test
    void successfulParseCompletesTheReviewAndPersistsComments() {
        Review review = persistRunningReview("sha-success");
        ReviewJob job = persistJob(review);

        CommentParser commentParser = new CommentParser(new GatewayProperties());

        ResultProcessor processor = newResultProcessor(commentParser);
        String raw = "[{\"file\":\"A.java\",\"line\":1,\"severity\":\"MAJOR\",\"comment\":\"Fix this\"}]";
        ReviewStatus finalStatus = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(raw, 10, 5, 1000L, "model-x"));

        assertThat(finalStatus).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(reviewResultRepository.existsByReviewId(review.getId())).isTrue();
        assertThat(reviewCommentRepository.findByReviewId(review.getId())).hasSize(1);
        assertThat(reviewCommentRepository.findByReviewId(review.getId()).get(0).getComment()).contains("Fix this");

        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.COMPLETED);

        ReviewJob reloadedJob = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloadedJob.getFinishedAt()).isNotNull();
    }

    @Test
    void resultIsIdempotentWhenReviewResultAlreadyExists() {
        Review review = persistRunningReview("sha-idempotent");
        ReviewJob job = persistJob(review);

        CommentParser commentParser = new CommentParser(new GatewayProperties());
        ResultProcessor processor = newResultProcessor(commentParser);

        // First delivery.
        processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("first raw response", 1, 1, 10L, "model-x"));

        long resultCountAfterFirst = reviewResultRepository.findByReviewId(review.getId()).stream().count();

        // Simulate a retried delivery of the exact same result after the Review already moved on
        // (ResultProcessor itself doesn't re-check RUNNING -- that's QueueManager's job -- but its
        // storeRawResult step must still be idempotent if ever invoked twice for the same review).
        processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("first raw response", 1, 1, 10L, "model-x"));

        assertThat(reviewResultRepository.findByReviewId(review.getId()).orElseThrow().getRawResponse())
                .isEqualTo("first raw response");
        assertThat(resultCountAfterFirst).isEqualTo(1);
    }

    // ---- F02-01/SR-21: raw response size cap ----

    @Test
    void oversizedRawResponseIsTruncatedBeforePersistAndParsing() {
        Review review = persistRunningReview("sha-oversized-raw");
        ReviewJob job = persistJob(review);

        GatewayProperties properties = new GatewayProperties();
        properties.getPublish().setMaxRawResponseLength(100);
        CommentParser commentParser = new CommentParser(properties);
        ResultProcessor processor = newResultProcessor(commentParser, properties);

        String oversizedRaw = "x".repeat(1000);
        ReviewStatus finalStatus = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(oversizedRaw, 10, 5, 1000L, "model-x"));

        assertThat(finalStatus).isEqualTo(ReviewStatus.COMPLETED);

        // Stored raw_response must be capped, never the full 1000-char payload (SR-21).
        String storedRaw = reviewResultRepository.findByReviewId(review.getId()).orElseThrow().getRawResponse();
        assertThat(storedRaw).hasSizeLessThanOrEqualTo(100);
        assertThat(storedRaw).contains("TRUNCATED");
        assertThat(storedRaw).doesNotContain("x".repeat(200)); // the full-length run of x's must not survive intact

        // The truncated (not the original) content is what CommentParser actually saw.
        assertThat(reviewCommentRepository.findByReviewId(review.getId())).hasSize(1);

        // Truncation fact (never raw content) is recorded in the audit trail alongside the COMPLETED event.
        List<com.review.gateway.model.ReviewEvent> events = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(review.getId());
        assertThat(events)
                .filteredOn(e -> e.getEventType() == com.review.gateway.model.enums.EventType.COMPLETED)
                .extracting(com.review.gateway.model.ReviewEvent::getDetails)
                .anySatisfy(details -> assertThat(details).contains("truncated"));
    }

    @Test
    void rawResponseWithinTheCapIsStoredUnchanged() {
        Review review = persistRunningReview("sha-within-cap");
        ReviewJob job = persistJob(review);

        GatewayProperties properties = new GatewayProperties();
        properties.getPublish().setMaxRawResponseLength(100);
        CommentParser commentParser = new CommentParser(properties);
        ResultProcessor processor = newResultProcessor(commentParser, properties);

        String withinCapRaw = "a normal, short model response";
        processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(withinCapRaw, 10, 5, 1000L, "model-x"));

        String storedRaw = reviewResultRepository.findByReviewId(review.getId()).orElseThrow().getRawResponse();
        assertThat(storedRaw).isEqualTo(withinCapRaw);

        List<com.review.gateway.model.ReviewEvent> events = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(review.getId());
        assertThat(events)
                .filteredOn(e -> e.getEventType() == com.review.gateway.model.enums.EventType.COMPLETED)
                .extracting(com.review.gateway.model.ReviewEvent::getDetails)
                .noneSatisfy(details -> assertThat(details).contains("truncated"));
    }
}

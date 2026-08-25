package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewResultRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.SubmitResultCommand;
import jakarta.persistence.EntityManager;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
    private ReviewChunkRepository reviewChunkRepository;
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
    @Autowired
    private EntityManager entityManager;

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
        EventService eventService = new EventService(reviewEventRepository, new TextSanitizer());
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        ChunkCoordinator chunkCoordinator = new ChunkCoordinator(reviewRepository, reviewJobRepository,
                reviewChunkRepository, reviewCommentRepository, stateMachine, jobStateMachine, properties, entityManager, transactionManager);
        RetryManager retryManager = new RetryManager(reviewJobRepository, jobStateMachine, chunkCoordinator,
                properties, new TextSanitizer(), entityManager, transactionManager);
        CommentRenderer commentRenderer = new CommentRenderer(commentParser, new TextSanitizer(), properties);
        StructuredResponseParser structuredResponseParser = new StructuredResponseParser(
                commentParser, commentRenderer, new TextSanitizer(), properties);
        return new ResultProcessor(reviewRepository, reviewJobRepository, reviewChunkRepository, reviewResultRepository,
                commentParser, structuredResponseParser, jobStateMachine, chunkCoordinator, retryManager,
                new MetricsCounters(), properties, entityManager, transactionManager);
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
        job.setStatus(JobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        return reviewJobRepository.saveAndFlush(job);
    }

    @Test
    void rawResponseIsStoredEvenWhenParsingFails() {
        Review review = persistRunningReview("sha-parse-fail");
        ReviewJob job = persistJob(review);

        CommentParser commentParser = Mockito.mock(CommentParser.class);
        when(commentParser.parse(eq("raw-broken"), anyInt())).thenThrow(new RuntimeException("boom"));

        ResultProcessor processor = newResultProcessor(commentParser);
        ReviewStatus finalStatus = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("raw-broken", 10, 5, 1000L, "model-x", null));

        assertThat(finalStatus).isEqualTo(ReviewStatus.FAILED);
        assertThat(reviewResultRepository.existsByReviewIdAndChunkIndex(review.getId(), 0)).isTrue();
        assertThat(reviewResultRepository.findByReviewIdAndChunkIndex(review.getId(), 0).orElseThrow().getRawResponse())
                .isEqualTo("raw-broken");
        assertThat(reviewCommentRepository.findByReviewId(review.getId())).isEmpty();

        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.FAILED);
    }

    @Test
    void successfulParseCompletesTheReviewAndPersistsComments() {
        Review review = persistRunningReview("sha-success");
        ReviewJob job = persistJob(review);

        CommentParser commentParser = new CommentParser(new GatewayProperties(), new MetricsCounters());

        ResultProcessor processor = newResultProcessor(commentParser);
        String raw = "[{\"file\":\"A.java\",\"line\":1,\"severity\":\"MAJOR\",\"comment\":\"Fix this\"}]";
        ReviewStatus finalStatus = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(raw, 10, 5, 1000L, "model-x", null));

        assertThat(finalStatus).isEqualTo(ReviewStatus.COMPLETED);
        assertThat(reviewResultRepository.existsByReviewIdAndChunkIndex(review.getId(), 0)).isTrue();
        assertThat(reviewCommentRepository.findByReviewId(review.getId())).hasSize(1);
        assertThat(reviewCommentRepository.findByReviewId(review.getId()).get(0).getComment()).contains("Fix this");

        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.COMPLETED);

        ReviewJob reloadedJob = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloadedJob.getFinishedAt()).isNotNull();
    }

    // ---- Structured Review Output: finish_reason propagation/normalization (SRO-42/43/44) ----

    @Test
    void recognizedFinishReasonIsStoredNormalizedLowercase() {
        Review review = persistRunningReview("sha-finish-length");
        ReviewJob job = persistJob(review);
        CommentParser commentParser = new CommentParser(new GatewayProperties(), new MetricsCounters());

        newResultProcessor(commentParser).process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("[]", 10, 5, 1000L, "model-x", "LENGTH"));

        assertThat(reviewResultRepository.findByReviewIdAndChunkIndex(review.getId(), 0).orElseThrow().getFinishReason())
                .isEqualTo("length");
    }

    @Test
    void nullFinishReasonIsStoredAsNullNotAsUnknown() {
        // SRO-44: an old Worker/backend build that omits the field must be distinguishable from a
        // genuinely-unrecognized value -- both must never be conflated.
        Review review = persistRunningReview("sha-finish-null");
        ReviewJob job = persistJob(review);
        CommentParser commentParser = new CommentParser(new GatewayProperties(), new MetricsCounters());

        newResultProcessor(commentParser).process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("[]", 10, 5, 1000L, "model-x", null));

        assertThat(reviewResultRepository.findByReviewIdAndChunkIndex(review.getId(), 0).orElseThrow().getFinishReason())
                .isNull();
    }

    @Test
    void unrecognizedFinishReasonIsNormalizedToUnknownNeverEnumValueOf() {
        Review review = persistRunningReview("sha-finish-unknown");
        ReviewJob job = persistJob(review);
        CommentParser commentParser = new CommentParser(new GatewayProperties(), new MetricsCounters());

        newResultProcessor(commentParser).process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("[]", 10, 5, 1000L, "model-x", "some-future-llama-cpp-value"));

        assertThat(reviewResultRepository.findByReviewIdAndChunkIndex(review.getId(), 0).orElseThrow().getFinishReason())
                .isEqualTo("unknown");
    }

    @Test
    void resultIsIdempotentWhenReviewResultAlreadyExists() {
        Review review = persistRunningReview("sha-idempotent");
        ReviewJob job = persistJob(review);

        CommentParser commentParser = new CommentParser(new GatewayProperties(), new MetricsCounters());
        ResultProcessor processor = newResultProcessor(commentParser);

        // First delivery.
        processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("first raw response", 1, 1, 10L, "model-x", null));

        long resultCountAfterFirst = reviewResultRepository.findByReviewIdAndChunkIndex(review.getId(), 0).stream().count();

        // Simulate a retried delivery of the exact same result after the Review already moved on
        // (ResultProcessor itself doesn't re-check RUNNING -- that's QueueManager's job -- but its
        // storeRawResult step must still be idempotent if ever invoked twice for the same review).
        processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("first raw response", 1, 1, 10L, "model-x", null));

        assertThat(reviewResultRepository.findByReviewIdAndChunkIndex(review.getId(), 0).orElseThrow().getRawResponse())
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
        CommentParser commentParser = new CommentParser(properties, new MetricsCounters());
        ResultProcessor processor = newResultProcessor(commentParser, properties);

        String oversizedRaw = "x".repeat(1000);
        ReviewStatus finalStatus = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(oversizedRaw, 10, 5, 1000L, "model-x", null));

        assertThat(finalStatus).isEqualTo(ReviewStatus.COMPLETED);

        // Stored raw_response must be capped, never the full 1000-char payload (SR-21).
        String storedRaw = reviewResultRepository.findByReviewIdAndChunkIndex(review.getId(), 0).orElseThrow().getRawResponse();
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
        CommentParser commentParser = new CommentParser(properties, new MetricsCounters());
        ResultProcessor processor = newResultProcessor(commentParser, properties);

        String withinCapRaw = "a normal, short model response";
        processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(withinCapRaw, 10, 5, 1000L, "model-x", null));

        String storedRaw = reviewResultRepository.findByReviewIdAndChunkIndex(review.getId(), 0).orElseThrow().getRawResponse();
        assertThat(storedRaw).isEqualTo(withinCapRaw);

        List<com.review.gateway.model.ReviewEvent> events = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(review.getId());
        assertThat(events)
                .filteredOn(e -> e.getEventType() == com.review.gateway.model.enums.EventType.COMPLETED)
                .extracting(com.review.gateway.model.ReviewEvent::getDetails)
                .noneSatisfy(details -> assertThat(details).contains("truncated"));
    }

    // ---- Structured Review Output: retry wiring, RETRY_THEN_FALLBACK, kill switch (SRO-35-41/68) ----

    private Review persistRunningStructuredReview(String headSha) {
        Review review = new Review(1L, 1L, headSha, "base", "v3", 10);
        review.setStatus(ReviewStatus.RUNNING);
        review.setAttempts(1);
        return reviewRepository.saveAndFlush(review);
    }

    private ReviewJob persistStructuredJob(Review review, int attempts) {
        Backend backend = backendRepository.saveAndFlush(
                new Backend("backend-struct-" + review.getId(), "https://backend-struct.local", "model", 1));
        ReviewJob job = new ReviewJob(review.getId(), backend.getId(), "worker-1");
        job.setStatus(JobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        job.setAttempts(attempts);
        return reviewJobRepository.saveAndFlush(job);
    }

    private void persistChunk(Long reviewId, String diff, List<String> filePaths) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < filePaths.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(filePaths.get(i)).append('"');
        }
        json.append(']');
        com.review.gateway.model.ReviewChunk chunk = new com.review.gateway.model.ReviewChunk(
                reviewId, 0, 1, diff, 10, filePaths.size(), json.toString());
        reviewChunkRepository.saveAndFlush(chunk);
    }

    private static final String SINGLE_FILE_DIFF =
            "diff --git a/A.java b/A.java\nindex 111..222 100644\n--- a/A.java\n+++ b/A.java\n@@ -1,1 +1,1 @@\n+x\n";

    @Test
    void structuredValidationFailureIsRequeuedWhenAttemptsRemain() {
        Review review = persistRunningStructuredReview("sha-struct-retry");
        persistChunk(review.getId(), SINGLE_FILE_DIFF, List.of("A.java"));
        ReviewJob job = persistStructuredJob(review, 1); // 1 < default max-attempts (3)

        CommentParser commentParser = new CommentParser(new GatewayProperties(), new MetricsCounters());
        ResultProcessor processor = newResultProcessor(commentParser);

        ReviewStatus status = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("not valid json", 10, 5, 1000L, "model-x", null));

        assertThat(status).isEqualTo(ReviewStatus.QUEUED);
        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(reloaded.getLastError()).startsWith("structured-output: NOT_JSON");
    }

    @Test
    void structuredValidationFailureOnTheLastAttemptFailsUnderDefaultRetryThenFail() {
        Review review = persistRunningStructuredReview("sha-struct-exhausted");
        persistChunk(review.getId(), SINGLE_FILE_DIFF, List.of("A.java"));
        ReviewJob job = persistStructuredJob(review, 3); // == default max-attempts

        CommentParser commentParser = new CommentParser(new GatewayProperties(), new MetricsCounters());
        ResultProcessor processor = newResultProcessor(commentParser);

        ReviewStatus status = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("not valid json", 10, 5, 1000L, "model-x", null));

        assertThat(status).isEqualTo(ReviewStatus.FAILED);
        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getLastError()).contains("structured-output: NOT_JSON");
    }

    @Test
    void coverageShortfallIsClassifiedAndRequeuedWithADiagnosticLastError() {
        Review review = persistRunningStructuredReview("sha-struct-coverage");
        persistChunk(review.getId(), SINGLE_FILE_DIFF, List.of("A.java", "B.java"));
        ReviewJob job = persistStructuredJob(review, 1);

        CommentParser commentParser = new CommentParser(new GatewayProperties(), new MetricsCounters());
        ResultProcessor processor = newResultProcessor(commentParser);
        // Only covers A.java -- B.java is missing.
        String raw = "{\"files\":{\"A.java\":{\"findings\":[],\"summary\":\"s\"}},\"summary\":\"y\"}";

        processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(raw, 10, 5, 1000L, "model-x", null));

        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(reloaded.getLastError()).startsWith("structured-output: COVERAGE_SHORTFALL");
        assertThat(reloaded.getLastError()).contains("B.java");
    }

    @Test
    void retryThenFallbackPublishesUnvalidatedCommentsWhenTheLegacyParserFindsARealJsonArray() {
        GatewayProperties properties = new GatewayProperties();
        properties.getStructured().setOnInvalidResponse("RETRY_THEN_FALLBACK");
        Review review = persistRunningStructuredReview("sha-struct-fallback-ok");
        persistChunk(review.getId(), SINGLE_FILE_DIFF, List.of("A.java"));
        ReviewJob job = persistStructuredJob(review, 3);

        CommentParser commentParser = new CommentParser(properties, new MetricsCounters());
        ResultProcessor processor = newResultProcessor(commentParser, properties);
        String legacyShaped = "[{\"file\":\"A.java\",\"line\":1,\"severity\":\"MAJOR\",\"comment\":\"legacy shaped\"}]";

        ReviewStatus status = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(legacyShaped, 10, 5, 1000L, "model-x", null));

        assertThat(status).isEqualTo(ReviewStatus.COMPLETED);
        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.COMPLETED);
        var comments = reviewCommentRepository.findByReviewId(review.getId());
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).getComment()).contains("UNVALIDATED");
        assertThat(comments.get(0).getComment()).contains("legacy shaped");
    }

    @Test
    void retryThenFallbackStillFailsWhenTheLegacyParserAlsoFindsNothing() {
        GatewayProperties properties = new GatewayProperties();
        properties.getStructured().setOnInvalidResponse("RETRY_THEN_FALLBACK");
        Review review = persistRunningStructuredReview("sha-struct-fallback-fail");
        persistChunk(review.getId(), SINGLE_FILE_DIFF, List.of("A.java"));
        ReviewJob job = persistStructuredJob(review, 3);

        CommentParser commentParser = new CommentParser(properties, new MetricsCounters());
        ResultProcessor processor = newResultProcessor(commentParser, properties);

        ReviewStatus status = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand("complete garbage, no brackets at all", 10, 5, 1000L, "model-x", null));

        assertThat(status).isEqualTo(ReviewStatus.FAILED);
        ReviewJob reloaded = reviewJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reviewCommentRepository.findByReviewId(review.getId())).isEmpty();
    }

    @Test
    void killSwitchOffRoutesAStructuredVersionThroughTheLegacyParser() {
        GatewayProperties properties = new GatewayProperties();
        properties.getStructured().setEnabled(false);
        Review review = persistRunningStructuredReview("sha-struct-killswitch");
        persistChunk(review.getId(), SINGLE_FILE_DIFF, List.of("A.java"));
        ReviewJob job = persistStructuredJob(review, 1);

        CommentParser commentParser = new CommentParser(properties, new MetricsCounters());
        ResultProcessor processor = newResultProcessor(commentParser, properties);
        String legacyShaped = "[{\"file\":\"A.java\",\"line\":1,\"severity\":\"MAJOR\",\"comment\":\"legacy path\"}]";

        ReviewStatus status = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(legacyShaped, 10, 5, 1000L, "model-x", null));

        assertThat(status).isEqualTo(ReviewStatus.COMPLETED);
        var comments = reviewCommentRepository.findByReviewId(review.getId());
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).getComment()).doesNotContain("UNVALIDATED");
        assertThat(comments.get(0).getComment()).contains("legacy path");
    }

    @Test
    void aConformingStructuredResponseCompletesNormallyAndPersistsRenderedComments() {
        Review review = persistRunningStructuredReview("sha-struct-success");
        persistChunk(review.getId(), SINGLE_FILE_DIFF, List.of("A.java"));
        ReviewJob job = persistStructuredJob(review, 1);

        CommentParser commentParser = new CommentParser(new GatewayProperties(), new MetricsCounters());
        ResultProcessor processor = newResultProcessor(commentParser);
        String raw = "{\"files\":{\"A.java\":{\"findings\":[{\"line\":1,\"severity\":\"major\","
                + "\"comment\":\"issue found\",\"suggestion\":\"\"}],\"summary\":\"s\"}},\"summary\":\"overall\"}";

        ReviewStatus status = processor.process(review.getId(), job.getId(), "worker-1", job.getBackendId(),
                new SubmitResultCommand(raw, 10, 5, 1000L, "model-x", null));

        assertThat(status).isEqualTo(ReviewStatus.COMPLETED);
        var comments = reviewCommentRepository.findByReviewId(review.getId());
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).getComment()).contains("issue found");
        assertThat(comments.get(0).getComment()).contains("**MAJOR**");
    }
}

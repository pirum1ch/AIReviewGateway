package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.IncompatiblePromptVersionException;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewChunk;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewPromptSectionRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.CreateReviewCommand;
import com.review.gateway.service.dto.CreateReviewResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end coverage of {@link ReviewService#createReview} chunk creation (V2, diff chunking) against
 * a real (Zonky) PostgreSQL instance: proves an oversized diff is actually split into N
 * {@code review_chunks}/{@code review_jobs} rows (not just rejected), that a single-chunk diff collapses
 * onto pre-V2 behavior (§8), and CSR-12's prompt-version allowlist is enforced when chunking is needed.
 *
 * <p>{@code @Transactional(NOT_SUPPORTED)}: {@code ReviewService.createReview} genuinely opens its own
 * separate transactions via {@code TransactionTemplate} (see {@code QueueManagerIntegrationTest}'s
 * javadoc for the identical rationale).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewServiceChunkingIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewInputRepository reviewInputRepository;
    @Autowired
    private ReviewChunkRepository reviewChunkRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewCommentRepository reviewCommentRepository;
    @Autowired
    private ReviewPromptSectionRepository reviewPromptSectionRepository;
    @Autowired
    private com.review.gateway.repository.ReviewEventRepository reviewEventRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
    }

    private ReviewService newReviewService(GatewayProperties properties) {
        EventService eventService = new EventService(reviewEventRepository, new TextSanitizer());
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        DeduplicationService deduplicationService = new DeduplicationService(reviewRepository);
        DiffSizeValidator diffSizeValidator = new DiffSizeValidator(properties);
        ChunkContextRenderer chunkContextRenderer = new ChunkContextRenderer(properties, new TextSanitizer());
        DiffChunker diffChunker = new DiffChunker(properties, diffSizeValidator, chunkContextRenderer);
        // Prompt Manager disabled in these pre-existing chunking tests (smallBudgetProperties() below)
        // -- PromptManager.resolve short-circuits to PromptResolution.none() without ever touching
        // gitLabClient, so a never-stubbed mock is safe here.
        PromptManager promptManager = new PromptManager(properties, Mockito.mock(GitLabClient.class),
                new PromptSourceResolver(properties), new PromptAssembler(properties, diffSizeValidator),
                new TextSanitizer());
        return new ReviewService(reviewRepository, reviewInputRepository, reviewChunkRepository,
                reviewJobRepository, reviewCommentRepository, reviewPromptSectionRepository, deduplicationService,
                diffSizeValidator, diffChunker, chunkContextRenderer, promptManager, eventService, stateMachine,
                jobStateMachine, entityManager, transactionManager);
    }

    private GatewayProperties smallBudgetProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().setEnabled(false);
        // Prompt Manager is disabled above (systemPromptTokens is always 0 here), but
        // DiffSizeValidator.assertPromptFits still checks the resulting budget against the configured
        // min-diff-budget-tokens floor (default 1000) regardless -- lower it to match this test's
        // deliberately tiny max-diff-tokens budget below, which exists purely to exercise chunking.
        properties.getPrompt().getLimits().setMinDiffBudgetTokens(0);
        properties.getDiff().setContextWindow(1_000_000);
        properties.getDiff().setPromptReserve(0);
        properties.getDiff().setAnswerReserve(0);
        properties.getDiff().setCharsPerToken(1);
        // Each ~94-char single-hunk section must fit alone (one section per chunk expected below), but
        // two together (~188 chars) must not -- and the whole 3-section diff (~282 chars) must exceed
        // this so the whole-diff shortcut never applies and chunking is actually exercised.
        properties.getDiff().setMaxDiffTokens(150);
        properties.getDiff().setChunkHeaderReserveTokens(0);
        properties.getDiff().setMaxChunks(5);
        return properties;
    }

    private String gitSection(String path, String body) {
        return "diff --git a/" + path + " b/" + path + "\n--- a/" + path + "\n+++ b/" + path
                + "\n@@ -1,1 +1,1 @@\n+" + body + "\n";
    }

    @Test
    void oversizedDiffIsSplitIntoMultipleChunksAndJobs() {
        GatewayProperties properties = smallBudgetProperties();
        String sectionA = gitSection("A.java", "a".repeat(20));
        String sectionB = gitSection("B.java", "b".repeat(20));
        String sectionC = gitSection("C.java", "c".repeat(20));
        String diff = sectionA + sectionB + sectionC; // well over the 60-token/char whole-diff shortcut

        ReviewService reviewService = newReviewService(properties);
        CreateReviewCommand command = new CreateReviewCommand(1L, 500L, "sha-chunked", "base", diff, "v2", 10);

        CreateReviewResult result = reviewService.createReview(command);

        assertThat(result.deduplicated()).isFalse();
        assertThat(result.status()).isEqualTo(ReviewStatus.QUEUED);

        List<ReviewChunk> chunks = reviewChunkRepository.findByReviewIdOrderByChunkIndexAsc(result.reviewId());
        List<ReviewJob> jobs = reviewJobRepository.findByReviewIdOrderByChunkIndexAsc(result.reviewId());
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(jobs).hasSameSizeAs(chunks);
        assertThat(chunks.get(0).getChunkCount()).isEqualTo(chunks.size());
        for (ReviewJob job : jobs) {
            assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        }

        // review_inputs still holds the whole, unmodified diff (re-run without hitting GitLab again).
        assertThat(reviewInputRepository.findByReviewId(result.reviewId()).orElseThrow().getDiff()).isEqualTo(diff);
    }

    @Test
    void chunkedReviewRequiresAChunkAwarePromptVersion() {
        GatewayProperties properties = smallBudgetProperties();
        String diff = gitSection("A.java", "a".repeat(20)) + gitSection("B.java", "b".repeat(20))
                + gitSection("C.java", "c".repeat(20));

        ReviewService reviewService = newReviewService(properties);
        CreateReviewCommand command = new CreateReviewCommand(1L, 501L, "sha-v1-chunked", "base", diff, "v1", 10);

        assertThatThrownBy(() -> reviewService.createReview(command))
                .isInstanceOf(IncompatiblePromptVersionException.class);

        assertThat(reviewRepository.findByProjectIdAndMergeRequestIdAndHeadShaAndStatusIn(
                1L, 501L, "sha-v1-chunked", DeduplicationService.ACTIVE_STATUSES)).isEmpty();
    }

    @Test
    void singleChunkDiffCollapsesOntoPreV2Behavior() {
        GatewayProperties properties = smallBudgetProperties();
        String smallDiff = gitSection("Only.java", "tiny change");

        ReviewService reviewService = newReviewService(properties);
        CreateReviewCommand command = new CreateReviewCommand(1L, 502L, "sha-single", "base", smallDiff, "v1", 10);

        CreateReviewResult result = reviewService.createReview(command);

        List<ReviewChunk> chunks = reviewChunkRepository.findByReviewIdOrderByChunkIndexAsc(result.reviewId());
        List<ReviewJob> jobs = reviewJobRepository.findByReviewIdOrderByChunkIndexAsc(result.reviewId());
        assertThat(chunks).hasSize(1);
        assertThat(jobs).hasSize(1);
        assertThat(chunks.get(0).getChunkCount()).isEqualTo(1);
        assertThat(chunks.get(0).getDiff()).isEqualTo(smallDiff);
        assertThat(jobs.get(0).getChunkIndex()).isZero();

        Review review = reviewRepository.findById(result.reviewId()).orElseThrow();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.QUEUED);
    }
}

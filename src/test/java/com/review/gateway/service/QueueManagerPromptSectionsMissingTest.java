package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewChunk;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.PromptBundleMode;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewPromptSectionRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.ClaimedJob;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PMR-09 (MUST): {@code prompt_bundle_mode=REPO} but zero {@code CORPORATE_*} sections at claim time
 * must fail the job explicitly — never dispatch it with an empty/partial system prompt. Also proves the
 * companion PMR-09 case (a {@code NONE}-mode Review still claims and runs normally) end to end against a
 * real (Zonky) PostgreSQL instance, matching {@code QueueManagerIntegrationTest}'s fixture conventions.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QueueManagerPromptSectionsMissingTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewInputRepository reviewInputRepository;
    @Autowired
    private ReviewChunkRepository reviewChunkRepository;
    @Autowired
    private ReviewCommentRepository reviewCommentRepository;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private ReviewPromptSectionRepository reviewPromptSectionRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUpCommittedRows() {
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private QueueManager newQueueManager() {
        EventService eventService = new EventService(reviewEventRepository);
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        BackendDispatcher backendDispatcher = new BackendDispatcher(backendRepository, reviewJobRepository);
        GatewayProperties properties = new GatewayProperties();
        ChunkCoordinator chunkCoordinator = new ChunkCoordinator(reviewRepository, reviewJobRepository,
                reviewChunkRepository, reviewCommentRepository, stateMachine, jobStateMachine, properties,
                entityManager, transactionManager);
        ChunkContextRenderer chunkContextRenderer = new ChunkContextRenderer(properties, new TextSanitizer());
        PromptMessageFormatter promptMessageFormatter = new PromptMessageFormatter(properties, new PromptAssembler(properties, new DiffSizeValidator(properties)));
        return new QueueManager(reviewRepository, reviewJobRepository, reviewChunkRepository,
                reviewPromptSectionRepository, backendDispatcher, jobStateMachine, chunkCoordinator, eventService,
                Mockito.mock(ResultProcessor.class), chunkContextRenderer, promptMessageFormatter, entityManager,
                transactionManager);
    }

    private Review persistQueuedReview(long mrId, String headSha, PromptBundleMode mode) {
        Review review = new Review(1L, mrId, headSha, "base", "v1", 10, mode);
        review.setStatus(ReviewStatus.QUEUED);
        review = reviewRepository.saveAndFlush(review);
        reviewInputRepository.saveAndFlush(new ReviewInput(review.getId(), "diff-" + headSha, "v1", headSha, "base", 10));
        ReviewChunk chunk = reviewChunkRepository.saveAndFlush(
                new ReviewChunk(review.getId(), 0, 1, "diff-" + headSha, 10, 0, "[]"));
        reviewJobRepository.saveAndFlush(new ReviewJob(review.getId(), chunk.getId(), 0, 10, null, null));
        return review;
    }

    private Backend persistBackend(String name) {
        Backend backend = new Backend(name, "https://" + name + ".local", "model-x", 2);
        backend.setStatus(BackendStatus.ACTIVE);
        return backendRepository.saveAndFlush(backend);
    }

    @Test
    void repoModeWithNoPersistedSectionsFailsTheJobInsteadOfDispatchingIt() {
        persistBackend("mac-mini-missing-sections");
        Review review = persistQueuedReview(900L, "sha-missing", PromptBundleMode.REPO);
        // Deliberately no review_prompt_sections rows persisted for this Review (simulating a retention
        // purge / kill-switch flip / partial transaction between create and claim).

        QueueManager queueManager = newQueueManager();
        Optional<ClaimedJob> claimed = queueManager.claim("mac-mini-missing-sections", "worker-1");

        assertThat(claimed).isEmpty();

        ReviewJob job = reviewJobRepository.findByReviewIdAndChunkIndex(review.getId(), 0).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(1); // the attempt WAS counted -- not silently requeued forever

        boolean hasMissingSectionsEvent = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(review.getId())
                .stream().anyMatch(e -> e.getEventType() == EventType.PROMPT_SECTIONS_MISSING);
        assertThat(hasMissingSectionsEvent).isTrue();

        // The parent Review's derived status must not go stale after a claim-time-only failure (recompute
        // still runs -- see QueueManager.claim's ClaimAttempt.reviewIdTouched()).
        Review reloaded = reviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.FAILED);
    }

    @Test
    void noneModeStillClaimsAndRunsNormallyWithNullSystemMessages() {
        persistBackend("mac-mini-none-mode");
        persistQueuedReview(901L, "sha-none", PromptBundleMode.NONE);

        QueueManager queueManager = newQueueManager();
        Optional<ClaimedJob> claimed = queueManager.claim("mac-mini-none-mode", "worker-1");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().systemMessages()).isNull();
    }

    @Test
    void repoModeWithPersistedCorporateSectionsClaimsSuccessfullyWithSystemMessages() {
        persistBackend("mac-mini-repo-ok");
        Review review = persistQueuedReview(902L, "sha-repo-ok", PromptBundleMode.REPO);
        reviewPromptSectionRepository.saveAndFlush(new com.review.gateway.model.ReviewPromptSection(
                review.getId(), 0, com.review.gateway.model.enums.PromptSectionKind.CORPORATE_BASE,
                com.review.gateway.model.enums.PromptSectionStatus.PRESENT, "corporate base content",
                "platform/prompts", "base.md", "main", "sha1", "hash1", 5));
        reviewPromptSectionRepository.saveAndFlush(new com.review.gateway.model.ReviewPromptSection(
                review.getId(), 1, com.review.gateway.model.enums.PromptSectionKind.CORPORATE_REVIEW_RULES,
                com.review.gateway.model.enums.PromptSectionStatus.PRESENT, "corporate rules content",
                "platform/prompts", "rules.md", "main", "sha1", "hash2", 5));

        QueueManager queueManager = newQueueManager();
        Optional<ClaimedJob> claimed = queueManager.claim("mac-mini-repo-ok", "worker-1");

        assertThat(claimed).isPresent();
        assertThat(claimed.get().systemMessages()).containsExactly("corporate base content", "corporate rules content");
    }
}

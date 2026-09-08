package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.DiffFetchUnavailableException;
import com.review.gateway.model.Review;
import com.review.gateway.repository.ReviewChunkRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewInputRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewPromptSectionRepository;
import com.review.gateway.repository.ReviewRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * True end-to-end coverage of {@link WebhookReviewTriggerService} against a real (Zonky) PostgreSQL —
 * the gap {@code WebhookReviewTriggerServiceTest} (mocks {@link ReviewService}/{@link ReviewRepository}
 * entirely) deliberately leaves open: does a webhook-triggered create actually persist a {@link Review}
 * row, and does the {@code (project_id, merge_request_id, head_sha)} dedup constraint really stop a
 * redelivery from creating a second one (plan §"Тесты": "проверка дедупликации и что транзиентный/
 * детерминированный сбой не создают Review-запись").
 *
 * <p>{@link GitLabClient} is mocked (its real HTTP behavior is {@code GitLabClientImplTest}'s job) —
 * only {@link ReviewService}/{@link ReviewRepository}/{@link DiffIntegrityVerifier}/{@link DiffAssembler}
 * are real, wired the same manual way {@code ReviewServicePromptManagerIntegrationTest} does.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookReviewTriggerServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long MR_IID = 5L;
    private static final Long BOT_USER_ID = 35L;
    private static final String BASE_SHA = "a".repeat(40);
    private static final String HEAD_SHA = "b".repeat(40);

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
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getWebhook().setEnabled(true);
        properties.getWebhook().setBotUserId(BOT_USER_ID);
        properties.getWebhook().setPromptVersion("v2");
        properties.getWebhook().setMaxReviewsPerProjectPerHour(100);
        properties.getWebhook().setMaxReviewsPerHour(1000);
        properties.getWebhook().setMaxConcurrentFetches(4);
        return properties;
    }

    private ReviewService newReviewService(GatewayProperties properties) {
        EventService eventService = new EventService(reviewEventRepository, new TextSanitizer());
        StateMachine stateMachine = new StateMachine(eventService);
        JobStateMachine jobStateMachine = new JobStateMachine(eventService);
        DeduplicationService deduplicationService = new DeduplicationService(reviewRepository);
        DiffSizeValidator diffSizeValidator = new DiffSizeValidator(properties);
        ChunkContextRenderer chunkContextRenderer = new ChunkContextRenderer(properties, new TextSanitizer());
        DiffChunker diffChunker = new DiffChunker(properties, diffSizeValidator, chunkContextRenderer);
        PromptManager promptManager = new PromptManager(properties, mock(GitLabClient.class),
                new PromptSourceResolver(properties), new PromptAssembler(properties, diffSizeValidator),
                new TextSanitizer());
        return new ReviewService(reviewRepository, reviewInputRepository, reviewChunkRepository,
                reviewJobRepository, reviewCommentRepository, reviewPromptSectionRepository, deduplicationService,
                diffSizeValidator, diffChunker, chunkContextRenderer, promptManager, eventService, stateMachine,
                jobStateMachine, new StructuredPathValidator(), properties, entityManager, transactionManager);
    }

    private WebhookReviewTriggerService newTriggerService(GatewayProperties properties, GitLabClient gitLabClient) {
        DiffIntegrityVerifier verifier =
                new DiffIntegrityVerifier(gitLabClient, new TextSanitizer(), new StructuredPathValidator(), properties);
        return new WebhookReviewTriggerService(properties, gitLabClient, verifier, new DiffAssembler(),
                newReviewService(properties), reviewRepository, new MetricsCounters());
    }

    private GitLabClient.MergeRequestSnapshot openMrWithBotAsReviewer() {
        return new GitLabClient.MergeRequestSnapshot("opened", BASE_SHA, HEAD_SHA, List.of(BOT_USER_ID));
    }

    private List<GitLabClient.DiffEntry> oneFileDiff() {
        return List.of(new GitLabClient.DiffEntry("A.java", "A.java", "100644", "100644",
                false, false, false, "@@ -1,1 +1,1 @@\n-old\n+new\n"));
    }

    @Test
    void happyPathPersistsARealReviewRow() {
        GatewayProperties properties = properties();
        GitLabClient gitLabClient = mock(GitLabClient.class);
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID)).thenReturn(openMrWithBotAsReviewer());
        when(gitLabClient.fetchOverflowFlag(PROJECT_ID, MR_IID)).thenReturn(false);
        when(gitLabClient.compareDiff(PROJECT_ID, BASE_SHA, HEAD_SHA))
                .thenReturn(new GitLabClient.CompareResult(false, oneFileDiff()));

        newTriggerService(properties, gitLabClient).handle(PROJECT_ID, MR_IID, null);

        List<Review> reviews = reviewRepository.findAll();
        assertThat(reviews).hasSize(1);
        assertThat(reviews.get(0).getProjectId()).isEqualTo(PROJECT_ID);
        assertThat(reviews.get(0).getMergeRequestId()).isEqualTo(MR_IID);
        assertThat(reviews.get(0).getHeadSha()).isEqualTo(HEAD_SHA);
    }

    @Test
    void aRedeliveryForTheSameHeadShaCreatesNoSecondReviewRow() {
        GatewayProperties properties = properties();
        GitLabClient gitLabClient = mock(GitLabClient.class);
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID)).thenReturn(openMrWithBotAsReviewer());
        when(gitLabClient.fetchOverflowFlag(PROJECT_ID, MR_IID)).thenReturn(false);
        when(gitLabClient.compareDiff(PROJECT_ID, BASE_SHA, HEAD_SHA))
                .thenReturn(new GitLabClient.CompareResult(false, oneFileDiff()));
        WebhookReviewTriggerService triggerService = newTriggerService(properties, gitLabClient);

        triggerService.handle(PROJECT_ID, MR_IID, null);
        triggerService.handle(PROJECT_ID, MR_IID, null); // simulated GitLab redelivery of the same event

        assertThat(reviewRepository.findAll()).hasSize(1);
    }

    @Test
    void aDeterministicIntegrityFailureCreatesNoReviewRow() {
        GatewayProperties properties = properties();
        GitLabClient gitLabClient = mock(GitLabClient.class);
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID)).thenReturn(openMrWithBotAsReviewer());
        when(gitLabClient.fetchOverflowFlag(PROJECT_ID, MR_IID)).thenReturn(true); // WHR-16: overflow -> reject
        when(gitLabClient.listRecentNotes(PROJECT_ID, MR_IID)).thenReturn(List.of());

        newTriggerService(properties, gitLabClient).handle(PROJECT_ID, MR_IID, null);

        assertThat(reviewRepository.findAll()).isEmpty();
        verify(gitLabClient, times(1)).postDiscussion(anyLong(), anyLong(), anyString());
    }

    @Test
    void aTransientFetchFailureCreatesNoReviewRowAndPostsNoComment() {
        GatewayProperties properties = properties();
        GitLabClient gitLabClient = mock(GitLabClient.class);
        when(gitLabClient.fetchMergeRequest(PROJECT_ID, MR_IID))
                .thenThrow(new DiffFetchUnavailableException("network blip"));

        newTriggerService(properties, gitLabClient).handle(PROJECT_ID, MR_IID, null);

        assertThat(reviewRepository.findAll()).isEmpty();
        verify(gitLabClient, never()).postDiscussion(anyLong(), anyLong(), anyString());
    }
}

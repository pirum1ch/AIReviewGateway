package com.review.gateway.repository;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewComment;
import com.review.gateway.model.ReviewEvent;
import com.review.gateway.model.ReviewInput;
import com.review.gateway.model.ReviewResult;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.model.enums.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers the remaining, simpler repositories not already exercised elsewhere. */
class MiscRepositoriesTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ReviewInputRepository reviewInputRepository;
    @Autowired
    private ReviewResultRepository reviewResultRepository;
    @Autowired
    private ReviewCommentRepository reviewCommentRepository;
    @Autowired
    private ReviewEventRepository reviewEventRepository;
    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private TestEntityManager entityManager;

    private Review persistReview(long projectId, long mrId, String headSha) {
        Review review = new Review(projectId, mrId, headSha, "base", "v1", 10);
        return entityManager.persistFlushFind(review);
    }

    @Test
    void reviewInputRepositoryFindsByReviewId() {
        Review review = persistReview(1L, 1L, "sha-input");
        entityManager.persistAndFlush(new ReviewInput(review.getId(), "diff", "v1", "sha-input", "base", 10));

        Optional<ReviewInput> found = reviewInputRepository.findByReviewId(review.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getDiff()).isEqualTo("diff");
    }

    @Test
    void reviewResultRepositoryExistsByReviewIdSupportsIdempotencyCheck() {
        Review review = persistReview(1L, 2L, "sha-result");

        assertThat(reviewResultRepository.existsByReviewId(review.getId())).isFalse();

        entityManager.persistAndFlush(new ReviewResult(review.getId(), "raw", "summary",
                10, 20, 30, 1000L, "model", null));

        assertThat(reviewResultRepository.existsByReviewId(review.getId())).isTrue();
        assertThat(reviewResultRepository.findByReviewId(review.getId())).isPresent();
    }

    @Test
    void reviewCommentRepositoryFindsOnlyUnpublishedComments() {
        Review review = persistReview(1L, 3L, "sha-comments");

        ReviewComment published = new ReviewComment(review.getId(), "A.java", 1, Severity.MINOR, "ok");
        published.setPublishedAt(Instant.now());
        published.setDiscussionId("d-1");
        entityManager.persistAndFlush(published);

        ReviewComment unpublished = new ReviewComment(review.getId(), "B.java", 2, Severity.CRITICAL, "bad");
        entityManager.persistAndFlush(unpublished);

        List<ReviewComment> unpublishedOnly = reviewCommentRepository.findByReviewIdAndPublishedAtIsNull(review.getId());
        assertThat(unpublishedOnly).extracting(ReviewComment::getId).containsExactly(unpublished.getId());

        assertThat(reviewCommentRepository.countByReviewId(review.getId())).isEqualTo(2);
        assertThat(reviewCommentRepository.findByReviewId(review.getId())).hasSize(2);
    }

    @Test
    void reviewEventRepositoryOrdersEventsByCreatedAt() throws InterruptedException {
        Review review = persistReview(1L, 4L, "sha-events");

        entityManager.persistAndFlush(new ReviewEvent(review.getId(), EventType.CREATED, null, null, "created"));
        Thread.sleep(5);
        entityManager.persistAndFlush(new ReviewEvent(review.getId(), EventType.CLAIMED, "worker-1", null, "claimed"));

        List<ReviewEvent> events = reviewEventRepository.findByReviewIdOrderByCreatedAtAsc(review.getId());

        assertThat(events).extracting(ReviewEvent::getEventType)
                .containsExactly(EventType.CREATED, EventType.CLAIMED);
    }

    @Test
    void backendRepositoryFindsByNameAndStatus() {
        entityManager.persistAndFlush(new Backend("bk-1", "https://bk-1.local", "model", 1));
        Backend suspect = new Backend("bk-2", "https://bk-2.local", "model", 1);
        suspect.setStatus(BackendStatus.SUSPECT);
        entityManager.persistAndFlush(suspect);

        assertThat(backendRepository.findByName("bk-1")).isPresent();
        assertThat(backendRepository.findByName("does-not-exist")).isEmpty();

        List<Backend> active = backendRepository.findByStatus(BackendStatus.ACTIVE);
        assertThat(active).extracting(Backend::getName).contains("bk-1");

        List<Backend> suspectBackends = backendRepository.findByStatus(BackendStatus.SUSPECT);
        assertThat(suspectBackends).extracting(Backend::getName).containsExactly("bk-2");
    }
}

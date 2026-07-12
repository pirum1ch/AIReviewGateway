package com.review.gateway.model;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.model.enums.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persists and reloads one row per table to prove every JPA mapping (columns, enum handling,
 * TIMESTAMPTZ &rarr; Instant, FK columns) round-trips correctly against the real Flyway-migrated
 * schema.
 */
class EntityRoundTripTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void allSevenTablesRoundTrip() {
        // reviews
        Review review = new Review(100L, 7L, "abc123", "def456", "v1", 20);
        review = entityManager.persistFlushFind(review);
        assertThat(review.getId()).isNotNull();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.NEW);
        assertThat(review.getPriority()).isEqualTo(20);
        assertThat(review.getAttempts()).isZero();
        assertThat(review.getCreatedAt()).isNotNull();
        assertThat(review.getUpdatedAt()).isNotNull();

        // review_inputs
        ReviewInput input = new ReviewInput(review.getId(), "diff --git a b", "v1", "abc123", "def456", 1234);
        input = entityManager.persistFlushFind(input);
        assertThat(input.getId()).isNotNull();
        assertThat(input.getReviewId()).isEqualTo(review.getId());
        assertThat(input.getDiff()).isEqualTo("diff --git a b");
        assertThat(input.getEstimatedTokens()).isEqualTo(1234);

        // backends
        Backend backend = new Backend("mac-mini-01", "https://mac-mini-01.local:8443", "qwen2.5-coder-32b", 2);
        backend = entityManager.persistFlushFind(backend);
        assertThat(backend.getId()).isNotNull();
        assertThat(backend.getStatus()).isEqualTo(BackendStatus.ACTIVE);

        // review_jobs
        ReviewJob job = new ReviewJob(review.getId(), backend.getId(), "worker-1");
        job.setHeartbeatAt(java.time.Instant.now());
        job.setClaimedAt(java.time.Instant.now());
        job.setStartedAt(java.time.Instant.now());
        job = entityManager.persistFlushFind(job);
        assertThat(job.getId()).isNotNull();
        assertThat(job.getReviewId()).isEqualTo(review.getId());
        assertThat(job.getBackendId()).isEqualTo(backend.getId());
        assertThat(job.getWorkerId()).isEqualTo("worker-1");

        // review_results
        ReviewResult result = new ReviewResult(review.getId(), "{\"raw\":true}", "summary text",
                100, 200, 300, 4500L, "qwen2.5-coder-32b", backend.getId());
        result = entityManager.persistFlushFind(result);
        assertThat(result.getId()).isNotNull();
        assertThat(result.getReviewId()).isEqualTo(review.getId());
        assertThat(result.getTotalTokens()).isEqualTo(300);

        // review_comments
        ReviewComment comment = new ReviewComment(review.getId(), "src/Main.java", 42, Severity.MAJOR,
                "Possible null dereference.");
        comment = entityManager.persistFlushFind(comment);
        assertThat(comment.getId()).isNotNull();
        assertThat(comment.getSeverity()).isEqualTo(Severity.MAJOR);
        assertThat(comment.getPublishedAt()).isNull();
        assertThat(comment.getDiscussionId()).isNull();

        comment.setDiscussionId("discussion-xyz");
        comment.setPublishedAt(java.time.Instant.now());
        comment = entityManager.persistFlushFind(comment);
        assertThat(comment.getDiscussionId()).isEqualTo("discussion-xyz");
        assertThat(comment.getPublishedAt()).isNotNull();

        // review_events
        ReviewEvent event = new ReviewEvent(review.getId(), EventType.CREATED, null, null, "created via API");
        event = entityManager.persistFlushFind(event);
        assertThat(event.getId()).isNotNull();
        assertThat(event.getEventType()).isEqualTo(EventType.CREATED);
        assertThat(event.getCreatedAt()).isNotNull();
    }
}

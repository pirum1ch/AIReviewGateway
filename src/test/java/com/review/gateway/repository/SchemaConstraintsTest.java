package com.review.gateway.repository;

import com.review.gateway.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Verifies DB-level guarantees that no JPA-level test can exercise, because the Java enums only
 * ever produce valid values: the {@code CHECK} constraints on {@code status}/{@code severity}/
 * {@code event_type} columns, and {@code ON DELETE CASCADE} from {@code reviews} to every child
 * table (architecture §3). Raw JDBC via {@link JdbcTemplate} is used deliberately to bypass the
 * entity layer and hit Postgres directly.
 *
 * <p>{@code NOT_SUPPORTED} propagation is used throughout so each statement runs in its own
 * auto-committed unit of work; a rejected {@code INSERT} would otherwise poison the shared
 * {@code @DataJpaTest} transaction for every subsequent statement in the same test method (Postgres
 * aborts the whole transaction after a constraint violation). Because that also disables the
 * framework's normal per-test rollback, every row this test class commits is explicitly deleted in
 * {@link #cleanUp()} — the embedded Postgres instance (and thus its data) is shared/reused across
 * test classes within one test run, so leaked {@code QUEUED} rows would otherwise silently corrupt
 * unrelated claim-ordering/dedup tests elsewhere in the suite.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SchemaConstraintsTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private final List<Long> createdReviewIds = new ArrayList<>();
    private final List<Long> createdBackendIds = new ArrayList<>();

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanUp() {
        // ON DELETE CASCADE takes care of any review_inputs/review_jobs/review_results/
        // review_comments/review_events rows created against these reviews.
        for (Long reviewId : createdReviewIds) {
            jdbc().update("DELETE FROM reviews WHERE id = ?", reviewId);
        }
        for (Long backendId : createdBackendIds) {
            jdbc().update("DELETE FROM backends WHERE id = ?", backendId);
        }
    }

    @Test
    void reviewsStatusCheckConstraintRejectsInvalidValue() {
        Throwable thrown = catchThrowable(() -> jdbc().update("""
                INSERT INTO reviews (project_id, merge_request_id, head_sha, base_sha, prompt_version, status, priority, attempts)
                VALUES (1, 1, 'sha-bad-status', 'base', 'v1', 'NOT_A_STATUS', 10, 0)
                """));

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage()).containsIgnoringCase("ck_reviews_status");
        // The constraint violation means no row was ever committed - nothing to clean up here.
    }

    @Test
    void backendsStatusCheckConstraintRejectsInvalidValue() {
        assertThatThrownBy(() -> jdbc().update("""
                INSERT INTO backends (name, url, model, capacity, status)
                VALUES ('bad-backend', 'https://bad.local', 'model', 1, 'BROKEN')
                """)).hasMessageContaining("ck_backends_status");
    }

    @Test
    void reviewCommentsSeverityCheckConstraintRejectsInvalidValue() {
        Long reviewId = insertReview("sha-bad-severity", 1);

        assertThatThrownBy(() -> jdbc().update("""
                INSERT INTO review_comments (review_id, comment, severity)
                VALUES (?, 'a comment', 'SUPER_BAD')
                """, reviewId)).hasMessageContaining("ck_comment_severity");
    }

    @Test
    void reviewCommentsSeverityAllowsNullBecauseColumnIsOptional() {
        Long reviewId = insertReview("sha-null-severity", 2);

        jdbc().update("""
                INSERT INTO review_comments (review_id, comment, severity)
                VALUES (?, 'a comment', NULL)
                """, reviewId);

        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM review_comments WHERE review_id = ?", Integer.class, reviewId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void reviewEventsTypeCheckConstraintRejectsInvalidValue() {
        Long reviewId = insertReview("sha-bad-event", 3);

        assertThatThrownBy(() -> jdbc().update("""
                INSERT INTO review_events (review_id, event_type)
                VALUES (?, 'NOT_A_REAL_EVENT')
                """, reviewId)).hasMessageContaining("ck_event_type");
    }

    @Test
    void deletingReviewCascadesToAllChildTables() {
        Long reviewId = insertReview("sha-cascade", 4);
        Long backendId = insertBackend("cascade-backend");

        jdbc().update("INSERT INTO review_inputs (review_id, diff, prompt_version, head_sha, base_sha) VALUES (?, 'diff', 'v1', 'sha-cascade', 'base')", reviewId);
        jdbc().update("INSERT INTO review_jobs (review_id, backend_id, worker_id) VALUES (?, ?, 'worker-1')", reviewId, backendId);
        jdbc().update("INSERT INTO review_results (review_id, raw_response) VALUES (?, 'raw')", reviewId);
        jdbc().update("INSERT INTO review_comments (review_id, comment) VALUES (?, 'a comment')", reviewId);
        jdbc().update("INSERT INTO review_events (review_id, event_type) VALUES (?, 'CREATED')", reviewId);

        assertThat(countWhereReviewId("review_inputs", reviewId)).isEqualTo(1);
        assertThat(countWhereReviewId("review_jobs", reviewId)).isEqualTo(1);
        assertThat(countWhereReviewId("review_results", reviewId)).isEqualTo(1);
        assertThat(countWhereReviewId("review_comments", reviewId)).isEqualTo(1);
        assertThat(countWhereReviewId("review_events", reviewId)).isEqualTo(1);

        jdbc().update("DELETE FROM reviews WHERE id = ?", reviewId);
        createdReviewIds.remove(reviewId); // already deleted above; avoid a harmless double-delete in cleanUp()

        assertThat(countWhereReviewId("review_inputs", reviewId)).isZero();
        assertThat(countWhereReviewId("review_jobs", reviewId)).isZero();
        assertThat(countWhereReviewId("review_results", reviewId)).isZero();
        assertThat(countWhereReviewId("review_comments", reviewId)).isZero();
        assertThat(countWhereReviewId("review_events", reviewId)).isZero();

        // The backend row itself is independent of the review and must survive.
        Integer backendCount = jdbc().queryForObject(
                "SELECT count(*) FROM backends WHERE id = ?", Integer.class, backendId);
        assertThat(backendCount).isEqualTo(1);
    }

    private int countWhereReviewId(String table, Long reviewId) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM " + table + " WHERE review_id = ?", Integer.class, reviewId);
    }

    private Long insertReview(String headSha, long mrId) {
        jdbc().update("""
                INSERT INTO reviews (project_id, merge_request_id, head_sha, base_sha, prompt_version, status, priority, attempts)
                VALUES (99, ?, ?, 'base', 'v1', 'QUEUED', 10, 0)
                """, mrId, headSha);
        Long reviewId = jdbc().queryForObject(
                "SELECT id FROM reviews WHERE project_id = 99 AND merge_request_id = ? AND head_sha = ?",
                Long.class, mrId, headSha);
        createdReviewIds.add(reviewId);
        return reviewId;
    }

    private Long insertBackend(String name) {
        jdbc().update("""
                INSERT INTO backends (name, url, model, capacity)
                VALUES (?, 'https://example.local', 'model', 1)
                """, name);
        Long backendId = jdbc().queryForObject("SELECT id FROM backends WHERE name = ?", Long.class, name);
        createdBackendIds.add(backendId);
        return backendId;
    }
}

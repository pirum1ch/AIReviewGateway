package com.review.gateway.repository;

import com.review.gateway.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@code FOR UPDATE SKIP LOCKED} claim query under genuine concurrency: two
 * separate physical connections/transactions racing for the same queued rows must each get a
 * distinct row and neither may block on the other (req. 1.3, architecture §5).
 *
 * <p>{@code propagation = NOT_SUPPORTED} disables {@code @DataJpaTest}'s default per-test
 * transaction rollback wrapper, because this test needs its setup rows to be actually committed
 * and visible to two independently-opened JDBC connections.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewRepositoryConcurrentClaimTest extends AbstractPostgresIntegrationTest {

    private static final String CLAIM_SQL = """
            SELECT r.id
            FROM reviews r
            WHERE r.status = 'QUEUED'
            ORDER BY r.priority DESC, r.created_at ASC
            FOR UPDATE SKIP LOCKED
            LIMIT 1
            """;

    @Autowired
    private DataSource dataSource;

    @Test
    void twoConcurrentClaimsEachGetADifferentRowWithoutBlocking() throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Long reviewA = insertQueuedReview(jdbcTemplate, 900L, 1L, "concurrent-sha-a");
        Long reviewB = insertQueuedReview(jdbcTemplate, 900L, 2L, "concurrent-sha-b");

        try {
            CountDownLatch firstClaimLocked = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Long> firstClaimant = executor.submit(claimTask(firstClaimLocked, true));
                Future<Long> secondClaimant = executor.submit(claimTask(firstClaimLocked, false));

                Long firstResult = firstClaimant.get(10, TimeUnit.SECONDS);
                Long secondResult = secondClaimant.get(10, TimeUnit.SECONDS);

                Set<Long> claimed = new HashSet<>();
                claimed.add(firstResult);
                claimed.add(secondResult);

                assertThat(firstResult).isNotNull();
                assertThat(secondResult).isNotNull();
                assertThat(firstResult).isNotEqualTo(secondResult);
                assertThat(claimed).containsExactlyInAnyOrder(reviewA, reviewB);
            } finally {
                executor.shutdownNow();
            }
        } finally {
            jdbcTemplate.update("DELETE FROM reviews WHERE id IN (?, ?)", reviewA, reviewB);
        }
    }

    /**
     * Strengthened version of the test above: N threads race, with no artificial ordering
     * (no latch, no sleep), over M QUEUED reviews where N == M. Each thread performs the full
     * claim-and-transition (architecture §5 step 2+3: {@code SELECT ... FOR UPDATE SKIP LOCKED}
     * then {@code UPDATE ... SET status = 'RUNNING'} in the same short transaction). If
     * {@code SKIP LOCKED} genuinely prevents double-claims, every one of the M reviews is claimed
     * by exactly one thread — no duplicates, no thread walks away empty-handed, no review is left
     * unclaimed.
     */
    @Test
    void nThreadsRacingOverNQueuedReviewsEachClaimExactlyOneDistinctRow() throws Exception {
        final int reviewCount = 12;
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        List<Long> reviewIds = new ArrayList<>();
        for (int i = 0; i < reviewCount; i++) {
            reviewIds.add(insertQueuedReview(jdbcTemplate, 950L, 100L + i, "race-sha-" + i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(reviewCount);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < reviewCount; i++) {
                futures.add(executor.submit(this::claimAndMarkRunning));
            }

            List<Long> claimedIds = new ArrayList<>();
            for (Future<Long> future : futures) {
                claimedIds.add(future.get(10, TimeUnit.SECONDS));
            }

            assertThat(claimedIds).doesNotContainNull();
            assertThat(claimedIds).as("no two threads may claim the same review")
                    .doesNotHaveDuplicates();
            assertThat(new HashSet<>(claimedIds))
                    .as("every queued review must end up claimed by exactly one thread")
                    .containsExactlyInAnyOrderElementsOf(reviewIds);
        } finally {
            executor.shutdownNow();
            jdbcTemplate.update(
                    "DELETE FROM reviews WHERE id IN (" +
                            reviewIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")");
        }
    }

    private Long claimAndMarkRunning() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            Long claimedId;
            try (PreparedStatement statement = connection.prepareStatement(CLAIM_SQL);
                 ResultSet resultSet = statement.executeQuery()) {
                claimedId = resultSet.next() ? resultSet.getLong(1) : null;
            }
            if (claimedId != null) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE reviews SET status = 'RUNNING' WHERE id = ?")) {
                    update.setLong(1, claimedId);
                    update.executeUpdate();
                }
            }
            connection.commit();
            return claimedId;
        }
    }

    private Callable<Long> claimTask(CountDownLatch firstClaimLocked, boolean isFirst) {
        return () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                if (!isFirst) {
                    // Wait until the first transaction has locked its row before racing for the rest.
                    assertThat(firstClaimLocked.await(10, TimeUnit.SECONDS)).isTrue();
                }
                Long claimedId;
                try (PreparedStatement statement = connection.prepareStatement(CLAIM_SQL);
                     ResultSet resultSet = statement.executeQuery()) {
                    claimedId = resultSet.next() ? resultSet.getLong(1) : null;
                }
                if (isFirst) {
                    firstClaimLocked.countDown();
                    // Hold the row lock open briefly so the second claimant is forced to skip it.
                    Thread.sleep(300);
                }
                connection.commit();
                return claimedId;
            }
        };
    }

    private Long insertQueuedReview(JdbcTemplate jdbcTemplate, long projectId, long mrId, String headSha) {
        jdbcTemplate.update("""
                INSERT INTO reviews (project_id, merge_request_id, head_sha, base_sha, prompt_version, status, priority, attempts)
                VALUES (?, ?, ?, 'base', 'v1', 'QUEUED', 10, 0)
                """, projectId, mrId, headSha);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM reviews WHERE project_id = ? AND merge_request_id = ? AND head_sha = ?",
                Long.class, projectId, mrId, headSha);
    }
}

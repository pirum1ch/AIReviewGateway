package com.review.gateway;

import com.review.gateway.model.Review;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA gap-fill: {@code GlobalExceptionHandler}'s mapping from a Postgres {@code lock_timeout}
 * (CSR-19's {@code SET LOCAL lock_timeout = '3s'}) to a clean {@code 409} was previously only
 * incidentally exercised by {@code ClaimCancelObsoleteConcurrencyTest}, which asserts "no 500" but
 * never actually forces a lock-timeout to occur — so it would pass silently even if the mapping were
 * broken or never triggered at all. This test deliberately holds a pessimistic lock on a Review row
 * open in a background thread/transaction for longer than the 3-second timeout, then asserts that a
 * concurrent {@code DELETE /reviews/{id}} on the SAME row gets a clean {@code 409 LOCK_TIMEOUT}, not an
 * indefinite hang or a raw {@code 500}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class LockTimeoutMappingTest {

    private static final String ADMIN_TOKEN = "test-admin-token-01234567890123456789012";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        reviewRepository.deleteAll();
    }

    private HttpEntity<?> adminRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + ADMIN_TOKEN);
        return new HttpEntity<>(headers);
    }

    @Test
    void concurrentCancelOnALockedRowReturnsCleanLockTimeoutNot500OrAnIndefiniteHang() throws Exception {
        Review review = new Review(1L, 1L, "sha-lock-timeout", "base", "v1", 10);
        review.setStatus(ReviewStatus.QUEUED);
        Review saved = reviewRepository.saveAndFlush(review);
        Long reviewId = saved.getId();

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // Background thread: opens its own transaction, locks the Review row FOR UPDATE, and holds
            // it open well past the 3s lock_timeout the concurrent DELETE call below will apply.
            Future<?> lockHolder = executor.submit(() -> {
                TransactionTemplate lockHoldingTransaction = new TransactionTemplate(transactionManager);
                lockHoldingTransaction.executeWithoutResult(status -> {
                    reviewRepository.findByIdForUpdate(reviewId).orElseThrow();
                    lockAcquired.countDown();
                    try {
                        // Longer than the 3s CSR-19 lock_timeout the DELETE call below is subject to.
                        releaseLock.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            });

            assertThat(lockAcquired.await(10, TimeUnit.SECONDS))
                    .as("background thread must have acquired the row lock before we proceed")
                    .isTrue();

            long start = System.currentTimeMillis();
            ResponseEntity<Map> response = restTemplate.exchange("/reviews/" + reviewId, HttpMethod.DELETE,
                    adminRequest(), Map.class);
            long elapsedMs = System.currentTimeMillis() - start;

            assertThat(response.getStatusCode().value())
                    .as("a contended row lock must surface as a clean 409, never a raw 500")
                    .isEqualTo(409);
            assertThat(response.getBody()).containsEntry("error", "LOCK_TIMEOUT");
            assertThat(elapsedMs)
                    .as("must resolve at (approximately) the 3s lock_timeout, not hang indefinitely")
                    .isLessThan(15_000L);

            releaseLock.countDown();
            lockHolder.get(15, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }

        // Once the background lock is released, the Review is untouched by the failed cancel attempt.
        Review reloaded = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ReviewStatus.QUEUED);
    }
}

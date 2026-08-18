package com.review.gateway.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.Backend;
import com.review.gateway.model.Review;
import com.review.gateway.model.ReviewJob;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.JobStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

/**
 * WOR-15(a) (Worker Observability &amp; Claim Latency, blocking MUST per {@code
 * docs/worker-observability-and-claim-latency-threat-model.md}): a {@code QUEUED} job whose {@code
 * not_before} has been in the past for longer than {@code gateway.job.max-duration} must be reported by
 * a WARN on the existing {@link BackendHealthChecker} scheduler tick (reusing the same pass as WOC-18 --
 * no new scheduled job), so a queue stall of this kind (backends healthy, a job nonetheless permanently
 * parked in {@code QUEUED} -- e.g. clock skew, a misconfigured {@code requeue-delay}, or a bug) can never
 * again be silent (WOT-08). Distinct from WOC-18's "0 eligible ACTIVE backend(s)" WARN, covered by
 * {@link BackendHealthCheckerTest}: this fires even with a perfectly healthy backend.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackendHealthCheckerStuckQueuedJobsTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUpLogCapture() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(BackendHealthChecker.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        ((Logger) LoggerFactory.getLogger(BackendHealthChecker.class)).detachAppender(logAppender);
    }

    @AfterEach
    void cleanUpCommittedRows() {
        reviewJobRepository.deleteAll();
        reviewRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private BackendHealthChecker newChecker(GatewayProperties properties) {
        BackendProber prober = mock(BackendProber.class);
        doNothing().when(prober).probe(Mockito.any());
        return new BackendHealthChecker(backendRepository, reviewJobRepository, prober, properties, transactionManager);
    }

    private GatewayProperties propertiesWithMaxDuration(Duration maxDuration) {
        GatewayProperties properties = new GatewayProperties();
        properties.getBackend().setFailureGrace(Duration.ofSeconds(180));
        properties.getHeartbeat().setTimeout(Duration.ofSeconds(180));
        properties.getJob().setMaxDuration(maxDuration);
        return properties;
    }

    private Backend persistHealthyActiveBackend(String name) {
        Backend backend = new Backend(name, "https://" + name + ".local", "model-x", 1);
        backend.setStatus(BackendStatus.ACTIVE);
        return backendRepository.saveAndFlush(backend);
    }

    private ReviewJob persistQueuedJob(long projectId, long mrId, String headSha, Instant notBefore) {
        Review review = new Review(projectId, mrId, headSha, "base", "v1", 10);
        review.setStatus(ReviewStatus.QUEUED);
        review = reviewRepository.saveAndFlush(review);
        ReviewJob job = new ReviewJob(review.getId(), null, null);
        job.setStatus(JobStatus.QUEUED);
        job.setNotBefore(notBefore);
        return reviewJobRepository.saveAndFlush(job);
    }

    private boolean anyWarnContains(String fragment) {
        return logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains(fragment));
    }

    @Test
    void queuedJobWithNotBeforeStuckPastMaxDurationProducesWarnEvenWithAHealthyBackend() {
        persistHealthyActiveBackend("mac-wor15a-stuck");
        GatewayProperties properties = propertiesWithMaxDuration(Duration.ofMinutes(45));
        persistQueuedJob(9L, 100L, "sha-wor15a-stuck", Instant.now().minus(Duration.ofMinutes(50)));

        newChecker(properties).probeAll();

        assertThat(anyWarnContains("not_before"))
                .as("WOR-15(a): a QUEUED job stuck past gateway.job.max-duration must produce a WARN even "
                        + "though the backend is healthy -- distinct from WOC-18's 0-ACTIVE-backend WARN")
                .isTrue();
    }

    @Test
    void queuedJobWithNotBeforeWithinMaxDurationProducesNoStuckWarn() {
        persistHealthyActiveBackend("mac-wor15a-fresh");
        GatewayProperties properties = propertiesWithMaxDuration(Duration.ofMinutes(45));
        // Ordinary requeue-delay wait -- not_before is in the recent past, well inside max-duration.
        persistQueuedJob(9L, 101L, "sha-wor15a-fresh", Instant.now().minus(Duration.ofSeconds(30)));

        newChecker(properties).probeAll();

        assertThat(anyWarnContains("not_before")).isFalse();
    }

    @Test
    void queuedJobWithNullNotBeforeNeverCountsAsStuck() {
        persistHealthyActiveBackend("mac-wor15a-null");
        GatewayProperties properties = propertiesWithMaxDuration(Duration.ofMinutes(45));
        // NULL not_before = immediately claimable; never "stuck" by this definition regardless of age.
        persistQueuedJob(9L, 102L, "sha-wor15a-null", null);

        newChecker(properties).probeAll();

        assertThat(anyWarnContains("not_before")).isFalse();
    }
}

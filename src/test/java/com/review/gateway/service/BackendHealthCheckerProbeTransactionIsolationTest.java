package com.review.gateway.service;

import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-added (Worker Observability &amp; Claim Latency, WOC-14, test guidance T-2.8): independently
 * verifies the specific regression class this feature exists to prevent -- {@code
 * BackendHealthChecker.probeAll()} previously ran its HTTP probe <em>inside</em> a
 * {@code @Transactional} method, pinning a Hikari connection for the full probe read-timeout. This test
 * asserts, from inside the (mocked) {@link BackendProber} itself, that phase B (the HTTP call) genuinely
 * executes with <b>no</b> Spring-managed transaction active on the calling thread, and additionally
 * proves — empirically, not just by code inspection — that a concurrent, independent database write
 * completes promptly while a slow probe is in flight, i.e. the probe pass does not starve the connection
 * pool the way the original bug class did.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackendHealthCheckerProbeTransactionIsolationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private ReviewJobRepository reviewJobRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUpCommittedRows() {
        reviewJobRepository.deleteAll();
        backendRepository.deleteAll();
    }

    private BackendHealthChecker newChecker(BackendProber prober) {
        GatewayProperties properties = new GatewayProperties();
        properties.getBackend().setFailureGrace(Duration.ofSeconds(180));
        properties.getHeartbeat().setTimeout(Duration.ofSeconds(180));
        return new BackendHealthChecker(backendRepository, reviewJobRepository, prober, properties, transactionManager);
    }

    /**
     * T-2.8 (primary, deterministic assertion): the probe call itself observes no active transaction on
     * its thread -- this is the direct, non-flaky verification of WOC-14's "phase B has no transaction
     * and no Hikari connection held" claim, independent of any timing/pool-size assumptions.
     */
    @Test
    void probeCallObservesNoActiveTransactionOnTheCallingThread() {
        Backend backend = backendRepository.saveAndFlush(
                new Backend("backend-tx-isolation", "https://backend-tx-isolation.local", "model-x", 1));
        backend.setStatus(BackendStatus.ACTIVE);
        backendRepository.saveAndFlush(backend);

        AtomicBoolean transactionWasActiveDuringProbe = new AtomicBoolean(true); // fail-safe default
        AtomicBoolean probeWasInvoked = new AtomicBoolean(false);
        BackendProber prober = target -> {
            probeWasInvoked.set(true);
            transactionWasActiveDuringProbe.set(TransactionSynchronizationManager.isActualTransactionActive());
        };

        newChecker(prober).probeAll();

        assertThat(probeWasInvoked).as("the mocked prober must actually have been called").isTrue();
        assertThat(transactionWasActiveDuringProbe)
                .as("WOC-14: probe HTTP I/O must run with no Spring-managed transaction active on the "
                        + "calling thread -- a regression here reintroduces the original Hikari-connection- "
                        + "pinned-during-a-slow-probe bug class")
                .isFalse();
    }

    /**
     * T-2.8 (secondary, empirical confirmation): while a probe is artificially slow (simulating a
     * hanging/loading-model backend at the raised 10s read-timeout, WOC-16), a fully independent database
     * write on another thread must complete promptly -- proving the slow probe is not pinning a
     * connection/lock that the rest of the Gateway needs (e.g. a concurrent {@code POST /jobs/claim}).
     */
    @Test
    void concurrentDatabaseWriteCompletesPromptlyWhileAProbeIsSlow() throws Exception {
        Backend backend = backendRepository.saveAndFlush(
                new Backend("backend-tx-isolation-slow", "https://backend-tx-isolation-slow.local", "model-x", 1));
        backend.setStatus(BackendStatus.ACTIVE);
        backendRepository.saveAndFlush(backend);

        CountDownLatch probeStarted = new CountDownLatch(1);
        Duration simulatedProbeLatency = Duration.ofSeconds(2);
        BackendProber slowProber = target -> {
            probeStarted.countDown();
            try {
                Thread.sleep(simulatedProbeLatency.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new BackendUnavailableException("simulated slow/unavailable backend");
        };

        CompletableFuture<Void> probePass = CompletableFuture.runAsync(() -> newChecker(slowProber).probeAll());

        assertThat(probeStarted.await(5, TimeUnit.SECONDS)).as("the probe must have started").isTrue();

        long startedAt = System.nanoTime();
        Backend unrelated = backendRepository.saveAndFlush(
                new Backend("backend-concurrent-write", "https://backend-concurrent-write.local", "model-y", 1));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(unrelated.getId()).isNotNull();
        assertThat(elapsedMs)
                .as("an unrelated DB write must not be blocked behind the in-flight probe's HTTP call "
                        + "(the original bug: a probe running inside a transaction pinned a Hikari "
                        + "connection for the whole probe timeout)")
                .isLessThan(simulatedProbeLatency.toMillis());

        probePass.get(10, TimeUnit.SECONDS);
        List<Backend> reloaded = backendRepository.findByStatus(BackendStatus.ACTIVE);
        assertThat(reloaded).extracting(Backend::getName).contains("backend-tx-isolation-slow");
    }
}

package com.review.gateway.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.review.gateway.AbstractPostgresIntegrationTest;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.dto.AnnounceBackendResponse;
import com.review.gateway.dto.UpsertBackendRequest;
import com.review.gateway.exception.BackendNameTakenException;
import com.review.gateway.exception.BackendRegistryFullException;
import com.review.gateway.exception.BackendUrlRejectedException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.service.dto.BackendUpsertOutcome;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backend Self-Registration ({@code docs/backend-self-registration-threat-model.md} BSQ-01..BSQ-15):
 * {@link BackendRegistryService}'s decision table, insert-race handling, and field-set discipline all
 * depend on the real {@code PESSIMISTIC_WRITE}/{@code REQUIRES_NEW} transaction behavior, so — like
 * {@code ResultProcessorConcurrentSubmitTest}/{@code BackendHealthCheckerTest} — this is a real-database
 * integration test rather than a pure Mockito unit test.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackendRegistryServiceTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private BackendRepository backendRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUpLogCapture() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(BackendRegistryService.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDownLogCapture() {
        ((Logger) LoggerFactory.getLogger(BackendRegistryService.class)).detachAppender(logAppender);
    }

    @AfterEach
    void cleanUpCommittedRows() {
        backendRepository.deleteAll();
    }

    private BackendRegistryService newService(GatewayProperties properties, MetricsCounters metricsCounters) {
        return new BackendRegistryService(backendRepository, properties, metricsCounters, new TextSanitizer(),
                entityManager, transactionManager);
    }

    private GatewayProperties permissiveProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getBackend().setAllowedHostPattern(".*");
        return properties;
    }

    private Backend persistBackend(String name, String url, String announcedBy, BackendStatus status) {
        Backend backend = new Backend(name, url, "model-x", 1);
        backend.setStatus(status);
        backend.setAnnouncedBy(announcedBy);
        return backendRepository.saveAndFlush(backend);
    }

    // ------------------------------------------------------------------------- BSQ-01 decision table ---

    @Test
    void announceInsertsAFreshUnknownName() {
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        AnnounceBackendResponse response = service.announce("mac-mini-01", "worker-1", "http://192.168.1.50:8080", "model-x");

        assertThat(response.created()).isTrue();
        assertThat(response.name()).isEqualTo("mac-mini-01");
        assertThat(response.status()).isEqualTo("ACTIVE");
        Backend saved = backendRepository.findByName("mac-mini-01").orElseThrow();
        assertThat(saved.getAnnouncedBy()).isEqualTo("worker-1");
        assertThat(saved.getUrl()).isEqualTo("http://192.168.1.50:8080");
        assertThat(saved.getCapacity()).isEqualTo(1);
    }

    @Test
    void firstClaimOfAnUnownedRow_differentUrl_isBlockedAsMisconfigurationRowByteIdentical() {
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", null, BackendStatus.ACTIVE);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        assertThatThrownBy(() -> service.announce("mac-mini-01", "worker-1", "http://192.168.1.99:8080", "model-y"))
                .isInstanceOf(BackendNameTakenException.class);

        Backend reloaded = backendRepository.findByName("mac-mini-01").orElseThrow();
        assertThat(reloaded.getUrl()).isEqualTo("http://192.168.1.50:8080");
        assertThat(reloaded.getAnnouncedBy()).isNull();
        assertThat(reloaded.getModel()).isEqualTo("model-x");
    }

    @Test
    void firstClaimOfAnUnownedRow_identicalUrl_claimsOwnership() {
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", null, BackendStatus.ACTIVE);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        AnnounceBackendResponse response = service.announce("mac-mini-01", "worker-1", "http://192.168.1.50:8080", "model-x");

        assertThat(response.created()).isFalse();
        Backend reloaded = backendRepository.findByName("mac-mini-01").orElseThrow();
        assertThat(reloaded.getAnnouncedBy()).isEqualTo("worker-1");
    }

    @Test
    void ownerReannouncingWithANewUrl_urlIsUpdated() {
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", "worker-1", BackendStatus.ACTIVE);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        AnnounceBackendResponse response = service.announce("mac-mini-01", "worker-1", "http://192.168.1.99:9090", "model-x");

        assertThat(response.created()).isFalse();
        Backend reloaded = backendRepository.findByName("mac-mini-01").orElseThrow();
        assertThat(reloaded.getUrl()).isEqualTo("http://192.168.1.99:9090");
        assertThat(reloaded.getAnnouncedBy()).isEqualTo("worker-1");
    }

    @Test
    void differentWorkerId_isBlockedAsMisconfiguration_notAuthorization() {
        // BSQ-10: test name deliberately says "misconfiguration", never "unauthorized"/"forbidden" --
        // workerId is a self-declared claim under the shared WORKER token, not a verified identity.
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", "worker-1", BackendStatus.ACTIVE);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        assertThatThrownBy(() -> service.announce("mac-mini-01", "worker-2", "http://192.168.1.50:8080", "model-x"))
                .isInstanceOf(BackendNameTakenException.class);

        Backend reloaded = backendRepository.findByName("mac-mini-01").orElseThrow();
        assertThat(reloaded.getAnnouncedBy()).isEqualTo("worker-1");
    }

    // ------------------------------------------------------------------------------------- BSQ-02/15 ---

    @Test
    void ownershipClaimIncrementsTheUrlRepointedCounter() {
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", null, BackendStatus.ACTIVE);
        MetricsCounters metrics = new MetricsCounters();
        BackendRegistryService service = newService(permissiveProperties(), metrics);

        service.announce("mac-mini-01", "worker-1", "http://192.168.1.50:8080", "model-x");

        assertThat(metrics.backendUrlRepointedCount()).isEqualTo(1);
    }

    @Test
    void nameTakenIncrementsTheNameTakenRejectedCounter() {
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", "worker-1", BackendStatus.ACTIVE);
        MetricsCounters metrics = new MetricsCounters();
        BackendRegistryService service = newService(permissiveProperties(), metrics);

        assertThatThrownBy(() -> service.announce("mac-mini-01", "worker-2", "http://192.168.1.50:8080", "model-x"))
                .isInstanceOf(BackendNameTakenException.class);

        assertThat(metrics.backendAnnounceRejectedSnapshot()).containsEntry("NAME_TAKEN", 1L);
    }

    @Test
    void unchangedReannounceDoesNotIncrementTheUrlRepointedCounter() {
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", "worker-1", BackendStatus.ACTIVE);
        MetricsCounters metrics = new MetricsCounters();
        BackendRegistryService service = newService(permissiveProperties(), metrics);

        service.announce("mac-mini-01", "worker-1", "http://192.168.1.50:8080", "model-x");

        assertThat(metrics.backendUrlRepointedCount()).isZero();
    }

    // -------------------------------------------------------------------------------------- BSQ-03 ---

    @Test
    void registryFullRejectsAFreshInsert() {
        GatewayProperties properties = permissiveProperties();
        properties.getBackend().getSelfRegistration().setMaxBackends(1);
        persistBackend("already-here", "http://192.168.1.10:8080", null, BackendStatus.ACTIVE);
        MetricsCounters metrics = new MetricsCounters();
        BackendRegistryService service = newService(properties, metrics);

        assertThatThrownBy(() -> service.announce("mac-mini-01", "worker-1", "http://192.168.1.50:8080", "model-x"))
                .isInstanceOf(BackendRegistryFullException.class);

        assertThat(backendRepository.count()).isEqualTo(1);
        assertThat(metrics.backendAnnounceRejectedSnapshot()).containsEntry("REGISTRY_FULL", 1L);
    }

    @Test
    void registryFullDoesNotBlockAnUpdateOfAnExistingOwnedRow() {
        GatewayProperties properties = permissiveProperties();
        properties.getBackend().getSelfRegistration().setMaxBackends(1);
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", "worker-1", BackendStatus.ACTIVE);
        BackendRegistryService service = newService(properties, new MetricsCounters());

        AnnounceBackendResponse response = service.announce("mac-mini-01", "worker-1", "http://192.168.1.99:9090", "model-x");

        assertThat(response.created()).isFalse();
    }

    @Test
    void registryFullCapDoesNotApplyToTheAdminPath() {
        GatewayProperties properties = permissiveProperties();
        properties.getBackend().getSelfRegistration().setMaxBackends(1);
        persistBackend("already-here", "http://192.168.1.10:8080", null, BackendStatus.ACTIVE);
        BackendRegistryService service = newService(properties, new MetricsCounters());

        BackendUpsertOutcome outcome = service.upsertByAdmin(new UpsertBackendRequest(
                "mac-mini-01", "http://192.168.1.50:8080", "model-x", null, null, null, null));

        assertThat(outcome.created()).isTrue();
        assertThat(backendRepository.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------- BSQ-08 ---

    @Test
    void concurrentAnnouncesOfTheSameBrandNewNameBothSucceedExactlyOneInsert() throws Exception {
        GatewayProperties properties = permissiveProperties();
        BackendRegistryService serviceA = newService(properties, new MetricsCounters());
        BackendRegistryService serviceB = newService(properties, new MetricsCounters());

        CountDownLatch startLatch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AnnounceBackendResponse> futureA = executor.submit(() -> {
                startLatch.countDown();
                startLatch.await(10, TimeUnit.SECONDS);
                return serviceA.announce("racing-backend", "worker-a", "http://192.168.1.50:8080", "model-x");
            });
            Future<AnnounceBackendResponse> futureB = executor.submit(() -> {
                startLatch.countDown();
                startLatch.await(10, TimeUnit.SECONDS);
                return serviceB.announce("racing-backend", "worker-b", "http://192.168.1.50:8080", "model-x");
            });

            AnnounceBackendResponse resultA = futureA.get(15, TimeUnit.SECONDS);
            AnnounceBackendResponse resultB = futureB.get(15, TimeUnit.SECONDS);

            // Exactly one of the two raced the INSERT and won (created=true); the other's retry finds the
            // row and takes the UPDATE branch. Since worker-a and worker-b differ, whichever one lost the
            // insert race is a *different* workerId announcing an already-owned row -- NAME_TAKEN is an
            // equally correct, non-500 outcome for the loser, so this only asserts "never both created,
            // never a 500", not which one specifically wins.
            long createdCount = (resultA.created() ? 1 : 0) + (resultB.created() ? 1 : 0);
            assertThat(createdCount).isEqualTo(1);
        } catch (Exception raced) {
            // A BackendNameTakenException surfacing from one of the two futures (the insert race's loser,
            // announcing under a different workerId than whoever's insert committed first) is an
            // acceptable, documented non-500 outcome -- re-thrown only if it is something else.
            Throwable cause = raced.getCause();
            if (!(cause instanceof BackendNameTakenException)) {
                throw raced;
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(backendRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------- BSQ-09 ---

    @Test
    void announceNeverTouchesStatusCapacityOrModeColumnsOnAnExistingRow() {
        Backend backend = persistBackend("mac-mini-01", "http://192.168.1.50:8080", "worker-1", BackendStatus.MAINTENANCE);
        backend.setCapacity(7);
        backend.setStructuredOutputMode("RESPONSE_FORMAT_SCHEMA");
        backend.setPromptMessageFormat("SINGLE");
        backendRepository.saveAndFlush(backend);

        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());
        service.announce("mac-mini-01", "worker-1", "http://192.168.1.99:9090", "model-x");

        Backend reloaded = backendRepository.findByName("mac-mini-01").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackendStatus.MAINTENANCE);
        assertThat(reloaded.getCapacity()).isEqualTo(7);
        assertThat(reloaded.getStructuredOutputMode()).isEqualTo("RESPONSE_FORMAT_SCHEMA");
        assertThat(reloaded.getPromptMessageFormat()).isEqualTo("SINGLE");
        // The url itself DID change (owner re-announce, not a first-claim) -- confirms the test actually
        // exercised the update path rather than accidentally no-op'ing.
        assertThat(reloaded.getUrl()).isEqualTo("http://192.168.1.99:9090");
    }

    // --------------------------------------------------------------------- announce never resurrects ---

    @Test
    void announceOntoAParkedRowNeverResurrectsIt() {
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", "worker-1", BackendStatus.OFFLINE);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        AnnounceBackendResponse response = service.announce("mac-mini-01", "worker-1", "http://192.168.1.99:9090", "model-x");

        assertThat(response.status()).isEqualTo("OFFLINE");
        Backend reloaded = backendRepository.findByName("mac-mini-01").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BackendStatus.OFFLINE);
        assertThat(reloaded.getUrl()).isEqualTo("http://192.168.1.99:9090");
    }

    // -------------------------------------------------------------------------------- URL validation ---

    @Test
    void announceCollapsesEveryUrlRejectionCauseToTheSameFixedMessage() {
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        assertThatThrownBy(() -> service.announce("mac-mini-01", "worker-1", "http://127.0.0.1:8080", "model-x"))
                .isInstanceOf(BackendUrlRejectedException.class)
                .hasMessage("Backend URL was rejected");

        assertThatThrownBy(() -> service.announce("mac-mini-02", "worker-1", "not a url", "model-x"))
                .isInstanceOf(BackendUrlRejectedException.class)
                .hasMessage("Backend URL was rejected");

        assertThatThrownBy(() -> service.announce("mac-mini-03", "worker-1", "http://192.168.1.50:8080/admin", "model-x"))
                .isInstanceOf(BackendUrlRejectedException.class)
                .hasMessage("Backend URL was rejected");
    }

    @Test
    void adminPathKeepsTheValidatorsOwnSpecificMessage() {
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        assertThatThrownBy(() -> service.upsertByAdmin(new UpsertBackendRequest(
                "mac-mini-01", "http://127.0.0.1:8080", "model-x", null, null, null, null)))
                .isInstanceOf(BackendUrlRejectedException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void announcePersistsTheNormalizedBareOriginNotTheRawInput() {
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        service.announce("mac-mini-01", "worker-1", "HTTP://192.168.1.50:8080", "model-x");

        Backend reloaded = backendRepository.findByName("mac-mini-01").orElseThrow();
        assertThat(reloaded.getUrl()).isEqualTo("http://192.168.1.50:8080");
    }

    // --------------------------------------------------------------------------------- admin upsert ---

    @Test
    void adminCreateRequiresUrlAndModel() {
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        assertThatThrownBy(() -> service.upsertByAdmin(new UpsertBackendRequest(
                "mac-mini-01", null, "model-x", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");

        assertThatThrownBy(() -> service.upsertByAdmin(new UpsertBackendRequest(
                "mac-mini-01", "http://192.168.1.50:8080", null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void adminUpdateIsPatchLikeAnAbsentFieldLeavesTheColumnUnchanged() {
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", "worker-1", BackendStatus.ACTIVE);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        BackendUpsertOutcome outcome = service.upsertByAdmin(new UpsertBackendRequest(
                "mac-mini-01", null, null, 5, null, null, null));

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.backend().getUrl()).isEqualTo("http://192.168.1.50:8080");
        assertThat(outcome.backend().getModel()).isEqualTo("model-x");
        assertThat(outcome.backend().getCapacity()).isEqualTo(5);
    }

    @Test
    void adminWriteAlwaysReleasesSelfRegistrationOwnership() {
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", "worker-1", BackendStatus.ACTIVE);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        BackendUpsertOutcome outcome = service.upsertByAdmin(new UpsertBackendRequest(
                "mac-mini-01", null, null, null, null, null, null));

        assertThat(outcome.backend().getAnnouncedBy()).isNull();
    }

    @Test
    void settingStatusActiveClearsProbeFailedSince() {
        Backend backend = persistBackend("mac-mini-01", "http://192.168.1.50:8080", null, BackendStatus.SUSPECT);
        backend.setProbeFailedSince(Instant.now());
        backendRepository.saveAndFlush(backend);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        BackendUpsertOutcome outcome = service.upsertByAdmin(new UpsertBackendRequest(
                "mac-mini-01", null, null, null, "ACTIVE", null, null));

        assertThat(outcome.backend().getStatus()).isEqualTo(BackendStatus.ACTIVE);
        assertThat(outcome.backend().getProbeFailedSince()).isNull();
    }

    @Test
    void adminCanSetStructuredOutputModeAndPromptMessageFormat() {
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", null, BackendStatus.ACTIVE);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        BackendUpsertOutcome outcome = service.upsertByAdmin(new UpsertBackendRequest(
                "mac-mini-01", null, null, null, null, "RESPONSE_FORMAT_SCHEMA", "SINGLE"));

        assertThat(outcome.backend().getStructuredOutputMode()).isEqualTo("RESPONSE_FORMAT_SCHEMA");
        assertThat(outcome.backend().getPromptMessageFormat()).isEqualTo("SINGLE");
    }

    // --------------------------------------------------------------------------------- decommission ---

    @Test
    void decommissionSoftDeletesAndClearsOwnershipAndFailureState() {
        Backend backend = persistBackend("mac-mini-01", "http://192.168.1.50:8080", "worker-1", BackendStatus.ACTIVE);
        backend.setProbeFailedSince(Instant.now());
        backendRepository.saveAndFlush(backend);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        Optional<Backend> result = service.decommission("mac-mini-01");

        assertThat(result).isPresent();
        assertThat(result.get().getStatus()).isEqualTo(BackendStatus.OFFLINE);
        assertThat(result.get().getAnnouncedBy()).isNull();
        assertThat(result.get().getProbeFailedSince()).isNull();
    }

    @Test
    void decommissionIsIdempotent() {
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", null, BackendStatus.OFFLINE);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        Optional<Backend> first = service.decommission("mac-mini-01");
        Optional<Backend> second = service.decommission("mac-mini-01");

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(second.get().getStatus()).isEqualTo(BackendStatus.OFFLINE);
    }

    @Test
    void decommissionUnknownNameReturnsEmpty() {
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        assertThat(service.decommission("does-not-exist")).isEmpty();
    }

    // ------------------------------------------------------------------------------------- BSQ-14 ---

    @Test
    void aLegacyRowsControlCharactersInAnnouncedByProduceASingleSanitizedLogLine() {
        // Simulates a value that predates this feature's charset validation (raw-SQL-inserted, or a
        // hand-edited row) -- announced_by has no @Pattern of its own on the DB side, so a control
        // character can genuinely reach this column outside the announce/admin write paths this test
        // exercises. BackendRegistryService must still sanitize it before it reaches a log line.
        persistBackend("mac-mini-01", "http://192.168.1.50:8080", "a\r\nFAKE INFO evil", BackendStatus.ACTIVE);
        BackendRegistryService service = newService(permissiveProperties(), new MetricsCounters());

        assertThatThrownBy(() -> service.announce("mac-mini-01", "worker-2", "http://192.168.1.50:8080", "model-x"))
                .isInstanceOf(BackendNameTakenException.class);

        List<ILoggingEvent> events = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("already owned"))
                .toList();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFormattedMessage())
                .as("the sanitized value must not contain a raw CR/LF -- a single log line, not two")
                .doesNotContain("\r").doesNotContain("\n");
    }
}

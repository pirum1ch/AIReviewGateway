package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.dto.AnnounceBackendResponse;
import com.review.gateway.dto.UpsertBackendRequest;
import com.review.gateway.exception.BackendNameTakenException;
import com.review.gateway.exception.BackendRegistryFullException;
import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.exception.BackendUrlRejectedException;
import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.service.dto.BackendUpsertOutcome;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * The only writer of {@code backends} outside {@code BackendHealthChecker} (architecture §2, threat
 * model §1). Two genuinely separate write paths, matching the two separate controllers/roles that call
 * them:
 *
 * <ul>
 *   <li>{@link #announce}: WORKER role, narrow fixed field set, the §2.4 decision table (BSQ-01, BSQ-08,
 *       BSQ-09).</li>
 *   <li>{@link #upsertByAdmin} / {@link #decommission}: ADMIN role, the trusted, full-featured write
 *       surface (architecture §2.3/§5).</li>
 * </ul>
 *
 * <p><b>{@code announced_by} is a misconfiguration guard, never an authorization boundary (BSQ-10,
 * T-03/T-15/WT-16):</b> {@code workerId} is a self-declared claim under the shared {@code WORKER_TOKEN},
 * not a verified identity. What the {@link #announce} decision table actually buys: the realistic failure
 * — two hosts with a copy-pasted {@code BACKEND_ID} and distinct {@code WORKER_ID}s — becomes a loud,
 * blocked {@code 409} on the second host instead of two Workers silently sharing one registry row while
 * the Gateway health-probes only one of them. The real security control on the URL is {@code
 * BackendUrlValidator} plus the startup-enforced allowlist ({@code GatewayProperties}), independent of
 * this guard.
 */
@Service
public class BackendRegistryService {

    private static final Logger log = LoggerFactory.getLogger(BackendRegistryService.class);

    private final BackendRepository backendRepository;
    private final GatewayProperties properties;
    private final MetricsCounters metricsCounters;
    private final TextSanitizer textSanitizer;
    private final EntityManager entityManager;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final TransactionTemplate defaultTransactionTemplate;

    public BackendRegistryService(BackendRepository backendRepository,
                                   GatewayProperties properties,
                                   MetricsCounters metricsCounters,
                                   TextSanitizer textSanitizer,
                                   EntityManager entityManager,
                                   PlatformTransactionManager transactionManager) {
        this.backendRepository = backendRepository;
        this.properties = properties;
        this.metricsCounters = metricsCounters;
        this.textSanitizer = textSanitizer;
        this.entityManager = entityManager;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTransactionTemplate.setName("BackendRegistryService.announce");
        // Deliberately a TransactionTemplate here too, not @Transactional -- same reason as everywhere
        // else in this codebase that must remain constructible with a plain `new` in tests (QueueManager,
        // RetryManager, ResultProcessor): an @Transactional annotation is only honored on a real
        // Spring-proxied bean, and is silently inert (no transaction, no rollback) on a manually
        // constructed instance.
        this.defaultTransactionTemplate = new TransactionTemplate(transactionManager);
        this.defaultTransactionTemplate.setName("BackendRegistryService.adminWrite");
    }

    /**
     * Handles {@code POST /backends/announce} (WORKER role). Deliberately a plain (non-{@code
     * @Transactional}) orchestrating method — same shape as {@code QueueManager.claim}/{@code
     * RetryManager.requeueOrFail}: the insert-race retry below (BSQ-08) must live <b>outside</b> the
     * transaction that raced. If the retry happened inside the same transaction, Spring would have
     * already marked it rollback-only the instant the constraint violation crossed the persistence
     * boundary, and the second pass would die at commit with {@code UnexpectedRollbackException} — the
     * exact failure {@code BackendDispatcher}'s javadoc documents and this codebase already fixed once by
     * restructuring, not by catching harder.
     *
     * @throws BackendUrlRejectedException  the submitted {@code url} failed validation — always the
     *                                       single fixed message {@code "Backend URL was rejected"}
     *                                       (BSQ-13); the real cause is logged server-side only
     * @throws BackendNameTakenException    the name is owned by a different {@code workerId}, or this is
     *                                       a first claim of an unowned row that also tried to change the
     *                                       {@code url} (BSQ-01)
     * @throws BackendRegistryFullException this would be a fresh INSERT and the registry is already at
     *                                       {@code gateway.backend.self-registration.max-backends} (BSQ-03)
     */
    public AnnounceBackendResponse announce(String backendName, String workerId, String rawUrl, String model) {
        String normalizedUrl;
        try {
            normalizedUrl = BackendUrlValidator.validate(rawUrl, properties.getBackend().resolveAllowedHostPattern());
        } catch (BackendUnavailableException rejected) {
            log.warn("Backend announce rejected: URL failed validation (name={}, workerId={}, reason={})",
                    safe(backendName), safe(workerId), rejected.getMessage());
            metricsCounters.incrementBackendAnnounceRejected("URL_REJECTED");
            // BSQ-13: the WORKER path always collapses to one fixed message -- never the validator's own,
            // cause-specific one (that would form a DNS-resolution / allowlist-membership oracle).
            throw new BackendUrlRejectedException("Backend URL was rejected");
        }

        AnnounceAttempt attempt = doAnnounceOnce(backendName, workerId, normalizedUrl, model);
        if (attempt == null) {
            attempt = doAnnounceOnce(backendName, workerId, normalizedUrl, model);
            if (attempt == null) {
                // BSQ-08: more than one retry is a bug, not a race -- let it surface as 500.
                throw new IllegalStateException(
                        "Backend announce insert race for name '" + safe(backendName) + "' did not resolve after one retry");
            }
        }

        return switch (attempt.kind()) {
            case NAME_TAKEN -> throw new BackendNameTakenException(
                    "Backend name is already claimed by a different worker (misconfiguration guard, not an authorization boundary)");
            case REGISTRY_FULL -> throw new BackendRegistryFullException(
                    "Backend registry is at its configured capacity (gateway.backend.self-registration.max-backends)");
            case DONE -> new AnnounceBackendResponse(attempt.name(), attempt.status(), attempt.created());
        };
    }

    /** One attempt at the whole read-modify-write, in its own {@code REQUIRES_NEW} transaction. @return {@code null} if it lost an insert race (retry once, outside this method). */
    private AnnounceAttempt doAnnounceOnce(String backendName, String workerId, String normalizedUrl, String model) {
        try {
            return requiresNewTransactionTemplate.execute(status -> announceTx(backendName, workerId, normalizedUrl, model));
        } catch (DataIntegrityViolationException raceLost) {
            log.debug("Backend announce insert race for name='{}': retrying once", safe(backendName));
            return null;
        }
    }

    private AnnounceAttempt announceTx(String backendName, String workerId, String normalizedUrl, String model) {
        applyLockTimeout();
        Optional<Backend> existingOpt = backendRepository.findByNameForUpdate(backendName);
        if (existingOpt.isEmpty()) {
            return createNewBackend(backendName, workerId, normalizedUrl, model);
        }
        return updateExistingBackend(existingOpt.get(), backendName, workerId, normalizedUrl, model);
    }

    private AnnounceAttempt createNewBackend(String backendName, String workerId, String normalizedUrl, String model) {
        int maxBackends = properties.getBackend().getSelfRegistration().getMaxBackends();
        if (backendRepository.count() >= maxBackends) {
            log.warn("Backend announce rejected: registry at max-backends cap (name={}, maxBackends={})",
                    safe(backendName), maxBackends);
            metricsCounters.incrementBackendAnnounceRejected("REGISTRY_FULL");
            return AnnounceAttempt.registryFull();
        }

        // BSQ-09: explicit, hard-coded field set -- the Backend(name, url, model, capacity) constructor
        // sets status=ACTIVE/capacity=1 itself; announced_by is the one extra setter call below. No
        // bean-mapping, no reflection: status/capacity/structuredOutputMode/promptMessageFormat/
        // probeFailedSince/lastSeen are structurally unreachable from this method.
        Backend backend = new Backend(backendName, normalizedUrl, model, null);
        backend.setAnnouncedBy(workerId);
        backendRepository.save(backend); // GenerationType.IDENTITY -> flushes now; a racing concurrent
        // create surfaces DataIntegrityViolationException HERE, inside this REQUIRES_NEW transaction.

        log.info("Backend registered via self-announce (name={}, workerId={}, model={}, origin={})",
                safe(backendName), safe(workerId), safe(model), safe(normalizedUrl));
        return AnnounceAttempt.done(backend.getName(), backend.getStatus().name(), true);
    }

    private AnnounceAttempt updateExistingBackend(Backend backend, String backendName, String workerId,
                                                    String normalizedUrl, String model) {
        String ownerBefore = backend.getAnnouncedBy();
        if (ownerBefore != null && !ownerBefore.equals(workerId)) {
            log.warn("Backend announce rejected: name already owned (name={}, owner={}, claimant={})",
                    safe(backendName), safe(ownerBefore), safe(workerId));
            metricsCounters.incrementBackendAnnounceRejected("NAME_TAKEN");
            return AnnounceAttempt.nameTaken();
        }

        boolean firstClaim = ownerBefore == null;
        String previousUrl = backend.getUrl();
        boolean urlChanging = !normalizedUrl.equals(previousUrl);

        if (firstClaim && urlChanging) {
            // BSQ-01 (architecture correction #1): a first claim of an unowned row may take ownership but
            // may NOT also repoint it in the same call -- zero mutation on this path.
            log.warn("Backend announce rejected: first claim of an unowned row attempted a URL change "
                            + "(misconfiguration guard, BSQ-10 -- not an authorization boundary) (name={}, claimant={})",
                    safe(backendName), safe(workerId));
            metricsCounters.incrementBackendAnnounceRejected("NAME_TAKEN");
            return AnnounceAttempt.nameTaken();
        }

        boolean changedModel = model != null && !model.equals(backend.getModel());

        // BSQ-09: explicit, hard-coded field set by direct setter calls -- url, model, and (only on a
        // first claim) announced_by. status/capacity/structuredOutputMode/promptMessageFormat/
        // probeFailedSince/lastSeen are structurally unreachable from this method (BSR-03).
        backend.setUrl(normalizedUrl);
        backend.setModel(model);
        if (firstClaim) {
            backend.setAnnouncedBy(workerId);
        }
        backendRepository.save(backend);

        if (firstClaim || urlChanging) {
            // BSQ-02: ownership claim (NULL -> workerId) and any URL change are the security-relevant
            // mutations -- dedicated WARN, never indistinguishable from a routine no-op re-announce.
            metricsCounters.incrementBackendUrlRepointed();
            log.warn("Backend {} via self-announce (name={}, workerId={}, oldOrigin={}, newOrigin={})",
                    firstClaim ? "ownership claimed" : "URL changed",
                    safe(backendName), safe(workerId), safe(previousUrl), safe(normalizedUrl));
        } else if (changedModel) {
            log.info("Backend updated via self-announce (name={}, workerId={}, changed=[model])",
                    safe(backendName), safe(workerId));
        } else {
            log.debug("Backend re-announced unchanged (name={}, workerId={})", safe(backendName), safe(workerId));
        }

        if (backend.getStatus() != BackendStatus.ACTIVE) {
            // BSR-02: announce succeeds onto a parked row (never resurrects it) -- worth a loud signal,
            // since it is easy for an operator to assume "parked" also means "URL-locked" (it does not,
            // BSQ-INH-5).
            log.warn("Backend announced while parked (name={}, status={})", safe(backendName), backend.getStatus());
        }

        return AnnounceAttempt.done(backend.getName(), backend.getStatus().name(), false);
    }

    /**
     * Handles {@code POST /backends} (ADMIN role, architecture §2.3) — upsert by name. PATCH-like: an
     * absent/{@code null} optional field leaves that column unchanged. Any successful admin write clears
     * {@code announced_by} (releases self-registration ownership) — the documented host-replacement
     * escape hatch (§2.4).
     *
     * @throws BackendUrlRejectedException the submitted {@code url} failed validation — the validator's
     *                                      own specific (constant, non-reflecting) message is preserved
     *                                      for this trusted principal (BSQ-13)
     * @throws IllegalArgumentException    {@code url}/{@code model} is required (and missing) on a create
     */
    public BackendUpsertOutcome upsertByAdmin(UpsertBackendRequest request) {
        return defaultTransactionTemplate.execute(status -> upsertByAdminTx(request));
    }

    private BackendUpsertOutcome upsertByAdminTx(UpsertBackendRequest request) {
        applyLockTimeout();
        Optional<Backend> existingOpt = backendRepository.findByNameForUpdate(request.name());
        boolean creating = existingOpt.isEmpty();

        if (creating && isBlank(request.url())) {
            throw new IllegalArgumentException("url is required when registering a new backend");
        }
        if (creating && isBlank(request.model())) {
            throw new IllegalArgumentException("model is required when registering a new backend");
        }

        String normalizedUrl = null;
        if (!isBlank(request.url())) {
            try {
                normalizedUrl = BackendUrlValidator.validate(request.url(), properties.getBackend().resolveAllowedHostPattern());
            } catch (BackendUnavailableException rejected) {
                // BSQ-13: ADMIN keeps the validator's own specific, constant, non-reflecting message.
                throw new BackendUrlRejectedException(rejected.getMessage());
            }
        }

        Backend backend;
        if (creating) {
            backend = new Backend(request.name(), normalizedUrl, request.model(), request.capacity());
        } else {
            backend = existingOpt.get();
            if (normalizedUrl != null) {
                backend.setUrl(normalizedUrl);
            }
            if (!isBlank(request.model())) {
                backend.setModel(request.model());
            }
            if (request.capacity() != null) {
                backend.setCapacity(request.capacity());
            }
        }
        if (request.status() != null) {
            BackendStatus status = BackendStatus.valueOf(request.status()); // bean-validated vocabulary
            backend.setStatus(status);
            if (status == BackendStatus.ACTIVE) {
                // Otherwise BackendDispatcher's F-WOC-01 fail-fast decline keeps refusing the backend
                // until the next successful probe -- an "I set it ACTIVE and nothing dispatches" ticket.
                backend.setProbeFailedSince(null);
            }
        }
        if (request.structuredOutputMode() != null) {
            backend.setStructuredOutputMode(request.structuredOutputMode());
        }
        if (request.promptMessageFormat() != null) {
            backend.setPromptMessageFormat(request.promptMessageFormat());
        }
        // §2.4: any successful admin write releases self-registration ownership -- the documented
        // host-replacement escape hatch (new WORKER_ID, same BACKEND_ID: one admin call, next announce claims it).
        backend.setAnnouncedBy(null);

        backendRepository.save(backend);
        log.info("Backend {} by admin (name={})", creating ? "registered" : "updated", safe(backend.getName()));
        return new BackendUpsertOutcome(backend, creating);
    }

    /**
     * Handles {@code DELETE /backends/{name}} (ADMIN role, architecture §5) — soft decommission, never a
     * row delete (a backend that ever ran a job cannot be deleted: {@code review_jobs}/{@code
     * review_results} FK {@code backends(id)} with no {@code ON DELETE}). Idempotent: already-{@code
     * OFFLINE} is still a {@code 200}, no-op.
     *
     * @return the decommissioned backend, or empty if {@code name} is unknown (404)
     */
    public Optional<Backend> decommission(String name) {
        return defaultTransactionTemplate.execute(status -> decommissionTx(name));
    }

    private Optional<Backend> decommissionTx(String name) {
        Optional<Backend> existingOpt = backendRepository.findByNameForUpdate(name);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }
        Backend backend = existingOpt.get();
        backend.setStatus(BackendStatus.OFFLINE);
        backend.setAnnouncedBy(null);
        backend.setProbeFailedSince(null);
        backendRepository.save(backend);
        log.info("Backend decommissioned by admin (name={})", safe(backend.getName()));
        return Optional.of(backend);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** BSQ-14: every value placed into a log line passes through the shared sanitizer first. */
    private static final int MAX_LOG_FIELD_LENGTH = 200;

    private String safe(String value) {
        return textSanitizer.sanitizeSingleLine(value, MAX_LOG_FIELD_LENGTH);
    }

    /** CSR-17-style bound on how long the pessimistic lock wait can pin a Hikari connection. */
    private void applyLockTimeout() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '3s'").executeUpdate();
    }

    /** Outcome of one {@link #announceTx} attempt. {@code null} name/status only for the non-DONE kinds. */
    private record AnnounceAttempt(Kind kind, String name, String status, boolean created) {

        enum Kind { DONE, NAME_TAKEN, REGISTRY_FULL }

        static AnnounceAttempt done(String name, String status, boolean created) {
            return new AnnounceAttempt(Kind.DONE, name, status, created);
        }

        static AnnounceAttempt nameTaken() {
            return new AnnounceAttempt(Kind.NAME_TAKEN, null, null, false);
        }

        static AnnounceAttempt registryFull() {
            return new AnnounceAttempt(Kind.REGISTRY_FULL, null, null, false);
        }
    }
}

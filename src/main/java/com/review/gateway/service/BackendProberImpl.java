package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.function.Supplier;

/**
 * Real HTTP-based {@link BackendProber} (architecture §11): {@code GET {backend.url}/health} with a
 * short timeout via a fresh {@code backendProbeRestClientFactory}-built client per probe (redirects
 * disabled, SR-10) — see that bean's Javadoc ({@code RestClientConfig}) for why a shared/pooled client is
 * unsafe here: a stale keep-alive connection surviving a remote {@code llama-server} restart can get
 * silently poisoned and never self-heal. The backend's URL is validated fresh on every probe by
 * {@link BackendUrlValidator} — never trusted just because it made it into the registry.
 *
 * <p>The fresh {@link ProbeClient} obtained from the factory on every call is closed via
 * try-with-resources the instant the probe completes (success, expected failure, or an unexpected
 * {@code Throwable} alike): the underlying {@code HttpClient} is {@code AutoCloseable} in JDK 21
 * specifically so its {@code SelectorManager} thread can be shut down promptly rather than relying on
 * GC/Cleaner-based finalization. Previously this client was never closed at all, leaking one such thread
 * per probe tick (~once/60s per backend) until the resulting resource pressure silently and permanently
 * killed the backend-health-check {@code @Scheduled} task — see {@code BackendHealthChecker} and
 * {@code ScheduledJobs} for the corresponding fix to the exception-handling gap that let that happen
 * unnoticed.
 *
 * <p>Replaces {@link NoOpBackendProber} as the Spring-managed {@link BackendProber} bean now that a real
 * implementation exists.
 */
@Component
public class BackendProberImpl implements BackendProber {

    private static final Logger log = LoggerFactory.getLogger(BackendProberImpl.class);
    private static final String HEALTH_PATH = "/health";

    private final Supplier<ProbeClient> backendProbeRestClientFactory;
    private final GatewayProperties properties;

    public BackendProberImpl(Supplier<ProbeClient> backendProbeRestClientFactory, GatewayProperties properties) {
        this.backendProbeRestClientFactory = backendProbeRestClientFactory;
        this.properties = properties;
    }

    @Override
    public void probe(Backend backend) {
        BackendUrlValidator.validate(backend.getUrl(), properties.getBackend().getAllowedHostPattern());

        try (ProbeClient probeClient = backendProbeRestClientFactory.get()) {
            probeClient.restClient().get()
                    .uri(backend.getUrl() + HEALTH_PATH)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failure) {
            log.debug("Backend '{}' health probe failed ({}): {}", backend.getName(),
                    failure.getClass().getSimpleName(), failure.getMessage());
            throw new BackendUnavailableException("Backend '" + backend.getName() + "' health probe failed", failure);
        }
    }
}

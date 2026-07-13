package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Real HTTP-based {@link BackendProber} (architecture §11): {@code GET {backend.url}/health} with a
 * short timeout via the dedicated {@code backendProbeRestClient} (redirects disabled, SR-10). The
 * backend's URL is validated fresh on every probe by {@link BackendUrlValidator} — never trusted just
 * because it made it into the registry.
 *
 * <p>Replaces {@link NoOpBackendProber} as the Spring-managed {@link BackendProber} bean now that a real
 * implementation exists.
 */
@Component
public class BackendProberImpl implements BackendProber {

    private static final Logger log = LoggerFactory.getLogger(BackendProberImpl.class);
    private static final String HEALTH_PATH = "/health";

    private final RestClient backendProbeRestClient;
    private final GatewayProperties properties;

    public BackendProberImpl(RestClient backendProbeRestClient, GatewayProperties properties) {
        this.backendProbeRestClient = backendProbeRestClient;
        this.properties = properties;
    }

    @Override
    public void probe(Backend backend) {
        BackendUrlValidator.validate(backend.getUrl(), properties.getBackend().getAllowedHostPattern());

        try {
            backendProbeRestClient.get()
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

package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Real HTTP-based {@link BackendProber} (architecture §11): {@code GET {backend.url}/health} with a
 * short timeout via a fresh {@code backendProbeRestClientFactory}-built client per probe (redirects
 * disabled, SR-10) — see that bean's Javadoc ({@code RestClientConfig}) for why a shared/pooled client is
 * unsafe here: a stale keep-alive connection surviving a remote {@code llama-server} restart can get
 * silently poisoned and never self-heal. The backend's URL is validated fresh on every probe by
 * {@link BackendUrlValidator} — never trusted just because it made it into the registry.
 *
 * <p>Replaces {@link NoOpBackendProber} as the Spring-managed {@link BackendProber} bean now that a real
 * implementation exists.
 */
@Component
public class BackendProberImpl implements BackendProber {

    private static final Logger log = LoggerFactory.getLogger(BackendProberImpl.class);
    private static final String HEALTH_PATH = "/health";

    private final Supplier<RestClient> backendProbeRestClientFactory;
    private final GatewayProperties properties;

    public BackendProberImpl(Supplier<RestClient> backendProbeRestClientFactory, GatewayProperties properties) {
        this.backendProbeRestClientFactory = backendProbeRestClientFactory;
        this.properties = properties;
    }

    @Override
    public void probe(Backend backend) {
        // BSQ-06: the compiled-once Pattern held by GatewayProperties, never recompiled per probe (falls
        // back to a fresh compile only for plain-unit-test callers that never ran validateOnStartup()).
        Pattern allowedHostPattern = properties.getBackend().resolveAllowedHostPattern();
        // BSQ-04/BSQ-07: validate() returns the normalized BARE ORIGIN -- this is what makes the
        // concatenation below safe. A stored url with a path/query/fragment is rejected here (on every
        // probe, not just at write time), so it can never reach the string-concat below.
        String origin = BackendUrlValidator.validate(backend.getUrl(), allowedHostPattern);

        try {
            RestClient freshClient = backendProbeRestClientFactory.get();
            freshClient.get()
                    .uri(origin + HEALTH_PATH)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failure) {
            log.debug("Backend '{}' health probe failed ({}): {}", backend.getName(),
                    failure.getClass().getSimpleName(), failure.getMessage());
            throw new BackendUnavailableException("Backend '" + backend.getName() + "' health probe failed", failure);
        }
    }
}

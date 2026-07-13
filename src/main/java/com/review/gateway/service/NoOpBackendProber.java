package com.review.gateway.service;

import com.review.gateway.model.Backend;

/**
 * Interim default {@link BackendProber} from feature/02-core-services: never fails, so every
 * {@code SUSPECT} backend probed with this implementation auto-recovers to {@code ACTIVE} on the next
 * health-check cycle and no {@code ACTIVE} backend is ever flipped to {@code SUSPECT}.
 *
 * <p>No longer a Spring bean (feature/03-api-security): {@link BackendProberImpl} is now the sole
 * {@code @Component}-registered {@link BackendProber} implementation, wired to the real
 * {@code backendProbeRestClient}. This class is kept only for tests/manual use that explicitly want a
 * no-op prober (e.g. constructing a {@code BackendHealthChecker} without any HTTP dependency); it is
 * never auto-wired.
 */
public class NoOpBackendProber implements BackendProber {

    @Override
    public void probe(Backend backend) {
        // Intentionally does nothing: see class javadoc.
    }
}

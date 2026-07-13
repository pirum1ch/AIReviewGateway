package com.review.gateway.service;

import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;

/**
 * Boundary interface to an actual health check against a registered llama-server backend
 * (architecture §11: {@code GET {backend.url}/health}). The real HTTP-backed implementation
 * ({@code RestClient}, SSRF-hardened per threat-model SR-10) arrives in feature/03-api-security;
 * {@link NoOpBackendProber} is the safe interim default so the application context can start before
 * then.
 */
public interface BackendProber {

    /**
     * @throws BackendUnavailableException if the backend did not respond healthily; a normal return
     *         (no exception) means the backend is reachable.
     */
    void probe(Backend backend);
}

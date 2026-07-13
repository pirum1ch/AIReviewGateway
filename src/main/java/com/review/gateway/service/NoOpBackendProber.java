package com.review.gateway.service;

import com.review.gateway.model.Backend;
import org.springframework.stereotype.Component;

/**
 * Interim default {@link BackendProber}: never fails, so every {@code SUSPECT} backend probed with
 * this implementation auto-recovers to {@code ACTIVE} on the next health-check cycle and no
 * {@code ACTIVE} backend is ever flipped to {@code SUSPECT}. This keeps the application usable (and
 * its context bootable) before feature/03-api-security supplies the real HTTP-based prober; it is not
 * a substitute for real health checking and must be replaced (or {@code @Primary}-overridden) before
 * relying on automatic backend suspension in production.
 */
@Component
public class NoOpBackendProber implements BackendProber {

    @Override
    public void probe(Backend backend) {
        // Intentionally does nothing: see class javadoc.
    }
}

package com.review.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Pairs a fresh per-probe {@link RestClient} with the resource backing it (the {@code HttpClient} in
 * production) so {@link BackendProberImpl#probe} can deterministically close it via try-with-resources
 * the instant the probe completes, success or failure.
 *
 * <p>Root cause this exists to fix: {@code RestClient} does not expose the {@code HttpClient}
 * underneath it, so a caller that only ever sees a bare {@code RestClient} has no way to close the
 * {@code java.net.http.HttpClient} JDK 21 made {@code AutoCloseable} specifically so its internal
 * {@code SelectorManager} thread could be shut down promptly instead of relying on GC/Cleaner-based
 * finalization. {@code BackendProberImpl} previously built a brand-new, never-closed {@code HttpClient}
 * on every single probe tick, leaking one {@code SelectorManager} thread per probe (confirmed via a live
 * thread dump: 100+ leaked {@code HttpClient-N-Worker}/{@code SelectorManager} threads over 46 minutes
 * of uptime) until resource pressure killed the backend-health-check {@code @Scheduled} task outright.
 */
public record ProbeClient(RestClient restClient, AutoCloseable resource) implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProbeClient.class);

    @Override
    public void close() {
        try {
            resource.close();
        } catch (Exception e) {
            // Best-effort: a cleanup failure must never mask (or be masked by) the probe's real outcome.
            log.debug("Failed to close probe HTTP resource", e);
        }
    }
}

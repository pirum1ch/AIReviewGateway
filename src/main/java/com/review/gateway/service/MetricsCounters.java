package com.review.gateway.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local, in-memory counters backing {@code GET /metrics} (WOR-03/§4.7, Worker Observability
 * &amp; Claim Latency). Deliberately <b>not</b> persisted to PostgreSQL: writing a {@code review_events}
 * row for a rejected/no-op {@code POST /jobs/{id}/fail} report would turn the endpoint into an
 * authenticated, unbounded {@code INSERT} primitive for any worker-token holder (§4.7 of the threat
 * model) — worse than the repudiation gap it would close. A process-local counter gives the same
 * detection signal (a guessing campaign is necessarily noisy) without that DoS/audit-pollution risk; it
 * resets on a Gateway restart, same as this project's existing re-entrancy flags (WOC-17) — this is
 * observability state, not reconstructible business state, so it does not fall under "PostgreSQL is the
 * single source of truth".
 */
@Component
public class MetricsCounters {

    private final Map<String, AtomicLong> ownershipMismatches = new ConcurrentHashMap<>();
    private final AtomicLong workerFailureReportsIgnored = new AtomicLong();

    /** @param endpoint a short, fixed label — e.g. {@code "heartbeat"}, {@code "result"}, {@code "fail"}. */
    public void incrementOwnershipMismatch(String endpoint) {
        ownershipMismatches.computeIfAbsent(endpoint, key -> new AtomicLong()).incrementAndGet();
    }

    /** WOR-03: every rejected (ownership-mismatch) or no-op (not-RUNNING) {@code /jobs/{id}/fail} report. */
    public void incrementWorkerFailureReportsIgnored() {
        workerFailureReportsIgnored.incrementAndGet();
    }

    public Map<String, Long> ownershipMismatchSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        ownershipMismatches.forEach((endpoint, count) -> snapshot.put(endpoint, count.get()));
        return snapshot;
    }

    public long workerFailureReportsIgnoredCount() {
        return workerFailureReportsIgnored.get();
    }
}

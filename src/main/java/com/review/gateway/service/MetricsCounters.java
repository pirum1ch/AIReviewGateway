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

    // Structured Review Output (SRO-45): every counter is keyed only on a closed Gateway vocabulary
    // (validation-failure kind, wire mode) -- never a file path, project id, backend URL, or any
    // model-supplied string (threat model SOR-21) -- exactly the same discipline as ownershipMismatches
    // above, which is keyed only on the fixed endpoint-name vocabulary.
    private final AtomicLong legacyParseFallback = new AtomicLong();
    private final Map<String, AtomicLong> structuredValidationFailures = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> structuredConstraintSent = new ConcurrentHashMap<>();
    private final AtomicLong structuredFallbackUsed = new AtomicLong();
    // Structured Output Grammar Budget (SGB-03/SOGB-11): keyed only on the fixed field-name vocabulary
    // ("comment"/"suggestion") -- never a file path, project id, or any model-supplied string.
    private final Map<String, AtomicLong> structuredFieldTruncated = new ConcurrentHashMap<>();

    // Backend Self-Registration (BSQ-15): keyed only on a closed Gateway-side reason vocabulary
    // (NAME_TAKEN/URL_REJECTED/VALIDATION/REGISTRY_FULL) -- never a backend name, workerId, or URL --
    // same discipline as structuredValidationFailures above. This is the detection signal for a
    // name-takeover or SSRF-probing campaign; the DB itself carries no durable trace (backends.updated_at
    // is overwritten by the very next successful health probe).
    private final Map<String, AtomicLong> backendAnnounceRejected = new ConcurrentHashMap<>();
    /** BSQ-15: every accepted ownership claim or URL change via self-announce (BSQ-02's WARN condition). */
    private final AtomicLong backendUrlRepointed = new AtomicLong();

    /** @param endpoint a short, fixed label — e.g. {@code "heartbeat"}, {@code "result"}, {@code "fail"}. */
    public void incrementOwnershipMismatch(String endpoint) {
        ownershipMismatches.computeIfAbsent(endpoint, key -> new AtomicLong()).incrementAndGet();
    }

    /** WOR-03: every rejected (ownership-mismatch) or no-op (not-RUNNING) {@code /jobs/{id}/fail} report. */
    public void incrementWorkerFailureReportsIgnored() {
        workerFailureReportsIgnored.incrementAndGet();
    }

    /**
     * SRO-45: every time {@code CommentParser}'s whole-response fallback path is taken — the single
     * cheapest instrument for symptom 2, and it measures today's v1/v2 traffic too, giving a genuine
     * baseline before any v3 Review exists (architecture §11 stage 0).
     */
    public void incrementLegacyParseFallback() {
        legacyParseFallback.incrementAndGet();
    }

    /** @param kind one of {@code StructuredResponseParser.FailureKind}'s names. */
    public void incrementStructuredValidationFailure(String kind) {
        structuredValidationFailures.computeIfAbsent(kind, key -> new AtomicLong()).incrementAndGet();
    }

    /** @param mode one of {@code StructuredOutputMode}'s names — how many claims actually carried a constraint. */
    public void incrementStructuredConstraintSent(String mode) {
        structuredConstraintSent.computeIfAbsent(mode, key -> new AtomicLong()).incrementAndGet();
    }

    /** SRO-38/68: every time the {@code RETRY_THEN_FALLBACK} escape hatch actually fires. */
    public void incrementStructuredFallbackUsed() {
        structuredFallbackUsed.incrementAndGet();
    }

    /**
     * SGB-03/SOGB-11: every time a structured finding's {@code comment}/{@code suggestion} was truncated
     * to the configured cap on receipt (never a rejection any more, see {@code StructuredResponseParser}).
     *
     * @param field a fixed Gateway constant -- {@code "comment"} or {@code "suggestion"} -- never a
     *              file path, line number, or any model-supplied text.
     */
    public void incrementStructuredFieldTruncated(String field) {
        structuredFieldTruncated.computeIfAbsent(field, key -> new AtomicLong()).incrementAndGet();
    }

    /** @param reason one of {@code NAME_TAKEN}/{@code URL_REJECTED}/{@code VALIDATION}/{@code REGISTRY_FULL}. */
    public void incrementBackendAnnounceRejected(String reason) {
        backendAnnounceRejected.computeIfAbsent(reason, key -> new AtomicLong()).incrementAndGet();
    }

    public void incrementBackendUrlRepointed() {
        backendUrlRepointed.incrementAndGet();
    }

    public Map<String, Long> ownershipMismatchSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        ownershipMismatches.forEach((endpoint, count) -> snapshot.put(endpoint, count.get()));
        return snapshot;
    }

    public long workerFailureReportsIgnoredCount() {
        return workerFailureReportsIgnored.get();
    }

    public long legacyParseFallbackCount() {
        return legacyParseFallback.get();
    }

    public Map<String, Long> structuredValidationFailuresSnapshot() {
        return snapshotOf(structuredValidationFailures);
    }

    public Map<String, Long> structuredConstraintSentSnapshot() {
        return snapshotOf(structuredConstraintSent);
    }

    public long structuredFallbackUsedCount() {
        return structuredFallbackUsed.get();
    }

    public Map<String, Long> structuredFieldTruncatedSnapshot() {
        return snapshotOf(structuredFieldTruncated);
    }

    public Map<String, Long> backendAnnounceRejectedSnapshot() {
        return snapshotOf(backendAnnounceRejected);
    }

    public long backendUrlRepointedCount() {
        return backendUrlRepointed.get();
    }

    private Map<String, Long> snapshotOf(Map<String, AtomicLong> counters) {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        counters.forEach((key, count) -> snapshot.put(key, count.get()));
        return snapshot;
    }
}

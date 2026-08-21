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
    /** DPR-12 (Diff Position Anchoring, SHOULD): every failure mode of position anchoring is silent by
     * design (it always degrades to a plain note), so without these an operator has no way to notice
     * "anchoring stopped working three weeks ago". Same process-local, non-persisted pattern as the two
     * counters above, for the same reason (an authenticated unbounded write primitive is a worse
     * trade-off than losing the counters on restart). */
    private final AtomicLong positionsAnchored = new AtomicLong();
    private final AtomicLong positionsUnresolved = new AtomicLong();
    private final AtomicLong diffRefsUnavailable = new AtomicLong();
    private final AtomicLong positionRejectedByGitLab = new AtomicLong();

    /** @param endpoint a short, fixed label — e.g. {@code "heartbeat"}, {@code "result"}, {@code "fail"}. */
    public void incrementOwnershipMismatch(String endpoint) {
        ownershipMismatches.computeIfAbsent(endpoint, key -> new AtomicLong()).incrementAndGet();
    }

    /** WOR-03: every rejected (ownership-mismatch) or no-op (not-RUNNING) {@code /jobs/{id}/fail} report. */
    public void incrementWorkerFailureReportsIgnored() {
        workerFailureReportsIgnored.incrementAndGet();
    }

    /** DPR-12: an unpublished comment with a resolvable file+line was successfully anchored to a diff position. */
    public void incrementPositionsAnchored() {
        positionsAnchored.incrementAndGet();
    }

    /** DPR-12: an unpublished comment had a file+line but no resolvable diff position was found for it. */
    public void incrementPositionsUnresolved() {
        positionsUnresolved.incrementAndGet();
    }

    /** DPR-12: {@code fetchDiffRefs} returned {@code Optional.empty()} (any reason — network, scope, stale MR state). */
    public void incrementDiffRefsUnavailable() {
        diffRefsUnavailable.incrementAndGet();
    }

    /** DPR-12: GitLab rejected a positioned POST with 400 and the automatic position-less retry ran (DPR-08). */
    public void incrementPositionRejectedByGitLab() {
        positionRejectedByGitLab.incrementAndGet();
    }

    public Map<String, Long> ownershipMismatchSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        ownershipMismatches.forEach((endpoint, count) -> snapshot.put(endpoint, count.get()));
        return snapshot;
    }

    public long workerFailureReportsIgnoredCount() {
        return workerFailureReportsIgnored.get();
    }

    public long positionsAnchoredCount() {
        return positionsAnchored.get();
    }

    public long positionsUnresolvedCount() {
        return positionsUnresolved.get();
    }

    public long diffRefsUnavailableCount() {
        return diffRefsUnavailable.get();
    }

    public long positionRejectedByGitLabCount() {
        return positionRejectedByGitLab.get();
    }
}

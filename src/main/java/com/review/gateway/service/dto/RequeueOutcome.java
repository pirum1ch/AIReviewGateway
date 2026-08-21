package com.review.gateway.service.dto;

/**
 * Outcome of {@code RetryManager.requeueOrFail} (WOC-28). {@code reviewId} is populated only when a job
 * row's status actually changed (so the caller knows to recompute the parent Review's derived status via
 * {@code ChunkCoordinator}) — {@code null} for every non-mutating outcome. Never surfaced to the Worker
 * directly: {@code QueueManager.reportFailure}/{@code JobController} map this to an opaque
 * {@code 200}/{@code 403}/{@code 404} with no {@code reviewId} in the body (WOC-28's own "reviewId is
 * used only for the internal ChunkCoordinator call and Gateway logging").
 */
public record RequeueOutcome(Outcome outcome, Long reviewId) {

    public enum Outcome {
        /** The job was requeued (attempts remaining) — {@code RUNNING -> QUEUED}. */
        APPLIED_REQUEUED,
        /** The job's attempts were exhausted — {@code RUNNING -> FAILED}. */
        APPLIED_FAILED,
        /** The job already left {@code RUNNING} by the time the lock was taken; nothing changed. */
        NOOP_NOT_RUNNING,
        /** No job with that id exists. */
        NOT_FOUND,
        /**
         * WOC-27: the locked re-check found a different {@code workerId} owns the job than the caller
         * asserted — no state was changed. Only possible when the caller supplied a non-null
         * {@code expectedWorkerId} (the sweep's two-arg call sites never do).
         */
        OWNERSHIP_MISMATCH
    }

    public static RequeueOutcome notFound() {
        return new RequeueOutcome(Outcome.NOT_FOUND, null);
    }

    public static RequeueOutcome ownershipMismatch() {
        return new RequeueOutcome(Outcome.OWNERSHIP_MISMATCH, null);
    }

    public static RequeueOutcome noopNotRunning() {
        return new RequeueOutcome(Outcome.NOOP_NOT_RUNNING, null);
    }

    public static RequeueOutcome requeued(Long reviewId) {
        return new RequeueOutcome(Outcome.APPLIED_REQUEUED, reviewId);
    }

    public static RequeueOutcome failed(Long reviewId) {
        return new RequeueOutcome(Outcome.APPLIED_FAILED, reviewId);
    }
}

package com.review.gateway.exception;

/**
 * Thrown by {@code BackendDispatcher} when a job cannot be claimed for the requested backend: the
 * backend name is unknown, the backend is not {@code ACTIVE}, or it has no free capacity (running
 * count &gt;= capacity). {@code QueueManager} catches this internally and reports "no job available"
 * (204) for the capacity/status cases; an unknown backend name propagates further so a future
 * {@code JobController} (feature/03-api-security) can map it to a diagnosable 409/404.
 */
public class JobNotClaimableException extends RuntimeException {

    public JobNotClaimableException(String message) {
        super(message);
    }
}

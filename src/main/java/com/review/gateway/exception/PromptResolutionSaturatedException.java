package com.review.gateway.exception;

/**
 * PMR-19: thrown when the bounded {@code gateway.prompt.limits.max-concurrent-resolutions} permit is
 * already fully held by other in-flight resolutions. Maps to an immediate {@code HTTP 503} — the
 * request never waits in a queue for a permit, so a GitLab outage/slowdown can never build up an
 * unbounded backlog of blocked Tomcat threads (which would eventually starve {@code /jobs/claim} and
 * heartbeats, requeuing tens of minutes of in-flight LLM work).
 */
public class PromptResolutionSaturatedException extends RuntimeException {

    public PromptResolutionSaturatedException(String message) {
        super(message);
    }
}

package com.review.gateway.exception;

/**
 * Thrown by a {@code GitLabClient} implementation when posting a discussion to GitLab fails
 * (network error, non-2xx response, timeout). {@code GitLabPublisher} treats this as transient: the
 * Review stays {@code COMPLETED} and publication is retried later by {@code PublishRetryService}
 * (req. 1.10).
 */
public class GitLabPublishException extends RuntimeException {

    public GitLabPublishException(String message) {
        super(message);
    }

    public GitLabPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}

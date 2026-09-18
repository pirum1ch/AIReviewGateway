package com.review.gateway.exception;

/**
 * Backend Self-Registration: thrown by {@code BackendRegistryService#upsertByAdminTx} when a create
 * request is missing a required {@code url}/{@code model} -- a condition bean validation on the DTO
 * cannot see (it cannot know whether the target row already exists). Mapped to {@code 400
 * VALIDATION_ERROR} by {@code GlobalExceptionHandler}, scoped to exactly this cause (F-BSR-01) rather
 * than to the application-wide {@code IllegalArgumentException} type -- the two throw sites both pass a
 * fixed, non-reflecting message.
 */
public class BackendValidationException extends RuntimeException {

    public BackendValidationException(String message) {
        super(message);
    }
}

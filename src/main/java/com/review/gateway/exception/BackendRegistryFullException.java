package com.review.gateway.exception;

/**
 * Backend Self-Registration (BSQ-03): thrown when an announce would INSERT a new {@code backends} row
 * while the registry is already at {@code gateway.backend.self-registration.max-backends}. Mapped to
 * {@code 422 BACKEND_REGISTRY_FULL} by {@code GlobalExceptionHandler} — deliberately a distinct code
 * (and status) from {@link BackendNameTakenException}'s {@code 409 BACKEND_NAME_TAKEN}, so the two
 * different "the announce did not land" causes remain distinguishable to a Worker (per the architecture
 * doc's endpoint table already reserving {@code 409} for name-taken specifically). The ADMIN write path
 * (a trusted principal that must always be able to fix things) is exempt from this cap.
 */
public class BackendRegistryFullException extends RuntimeException {

    public BackendRegistryFullException(String message) {
        super(message);
    }
}

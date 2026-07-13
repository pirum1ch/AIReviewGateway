package com.review.gateway.controller;

/** Mirrors the dummy (but valid, SR-01 >=32 char) tokens configured in {@code src/test/resources/application.yml}. */
final class SecurityTestTokens {

    static final String CI_TOKEN = "test-ci-token-01234567890123456789012345";
    static final String WORKER_TOKEN = "test-worker-token-0123456789012345678901";
    static final String ADMIN_TOKEN = "test-admin-token-01234567890123456789012";

    private SecurityTestTokens() {
    }
}

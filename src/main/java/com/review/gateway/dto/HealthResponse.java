package com.review.gateway.dto;

/** {@code GET /health} public liveness body (architecture §2 root — distinct from {@code /actuator/health}). */
public record HealthResponse(String status) {

    public static HealthResponse up() {
        return new HealthResponse("UP");
    }
}

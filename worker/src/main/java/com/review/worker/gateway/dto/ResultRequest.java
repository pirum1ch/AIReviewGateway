package com.review.worker.gateway.dto;

/** Mirrors the Gateway's {@code com.review.gateway.dto.SubmitResultRequest} field-for-field. */
public record ResultRequest(
        String workerId,
        String rawResponse,
        Integer promptTokens,
        Integer completionTokens,
        Long durationMs,
        String model) {
}

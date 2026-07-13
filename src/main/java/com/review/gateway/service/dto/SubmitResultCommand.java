package com.review.gateway.service.dto;

/**
 * Input to {@code QueueManager#submitResult} (architecture §11 {@code SubmitResultRequest}).
 */
public record SubmitResultCommand(
        String rawResponse,
        Integer promptTokens,
        Integer completionTokens,
        Long durationMs,
        String model) {
}

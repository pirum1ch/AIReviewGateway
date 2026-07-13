package com.review.gateway.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /jobs/{id}/result} request body (architecture §11 omits {@code workerId}; added here as
 * a deliberate spec-completion for the same reason as {@link ClaimJobRequest} — SR-04 ownership needs
 * the caller's identity, which every other Worker-facing endpoint already carries in its body).
 */
public record SubmitResultRequest(
        @NotBlank String workerId,
        @NotBlank String rawResponse,
        Integer promptTokens,
        Integer completionTokens,
        Long durationMs,
        String model) {
}

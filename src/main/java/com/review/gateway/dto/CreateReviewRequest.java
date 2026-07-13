package com.review.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code POST /reviews} request body (architecture §11), CI-facing. Field-level validation rejects
 * structurally-invalid requests before any service logic runs (fail-fast at the edge).
 */
public record CreateReviewRequest(
        @NotNull @Positive Long projectId,
        @NotNull @Positive Long mergeRequestId,
        @NotBlank String headSha,
        @NotBlank String baseSha,
        @NotBlank String diff,
        @NotBlank String promptVersion,
        Integer priority) {
}

package com.review.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /reviews} request body (architecture §11), CI-facing. Field-level validation rejects
 * structurally-invalid requests before any service logic runs (fail-fast at the edge).
 *
 * <p><b>F-SRO-09 (appsec SAST fix round):</b> {@code promptVersion} gains {@code @Size}/{@code @Pattern}
 * — previously {@code @NotBlank} only, so an arbitrarily long/arbitrary-content value could reach
 * {@code ReviewService}'s {@code STRUCTURED_OUTPUT_UNSUPPORTED}/allowlist-rejection messages, which
 * (unlike the SRO-16/17/65 throw sites) echoed the raw value verbatim into the {@code 422} response body
 * (the exact pattern F-DC-06 established must not happen for attacker-controlled text). The bound also
 * matches {@code reviews.prompt_version}'s own {@code VARCHAR(32)} column width.
 */
public record CreateReviewRequest(
        @NotNull @Positive Long projectId,
        @NotNull @Positive Long mergeRequestId,
        @NotBlank String headSha,
        @NotBlank String baseSha,
        @NotBlank String diff,
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9._-]{1,32}$") String promptVersion,
        Integer priority) {
}

package com.review.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /jobs/{id}/result} request body (architecture §11 omits {@code workerId}; added here as
 * a deliberate spec-completion for the same reason as {@link ClaimJobRequest} — SR-04 ownership needs
 * the caller's identity, which every other Worker-facing endpoint already carries in its body).
 *
 * <p><b>WOR-06:</b> {@code workerId} gains {@code @Size(max = 64)} + {@code @Pattern} like the other
 * three Worker-facing DTOs (CRLF/control-char log-injection, {@code VARCHAR(64)}-overflow 500).
 */
public record SubmitResultRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]{1,64}$") String workerId,
        @NotBlank String rawResponse,
        Integer promptTokens,
        Integer completionTokens,
        Long durationMs,
        String model) {
}

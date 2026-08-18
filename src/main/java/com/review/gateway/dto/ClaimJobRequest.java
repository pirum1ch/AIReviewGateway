package com.review.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /jobs/claim} request body (architecture §11 names only {@code backendId}; {@code
 * workerId} is added here as a deliberate spec-completion — {@code QueueManager#claim} requires a
 * worker identity to attribute the claim/heartbeat/result ownership chain (SR-04/SR-05), and the
 * architecture's own {@code HeartbeatRequest}/{@code SubmitResultRequest} both carry one, so claim
 * must too).
 *
 * <p><b>WOR-06:</b> {@code @Size(max = 64)} matches the {@code VARCHAR(64)} column width for both fields
 * (removes a pre-existing 500 on overflow) and {@code @Pattern} bounds the character set before either
 * value is ever logged or persisted (CRLF/control-char log-injection).
 */
public record ClaimJobRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]{1,64}$") String backendId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]{1,64}$") String workerId) {
}

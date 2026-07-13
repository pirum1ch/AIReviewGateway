package com.review.gateway.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /jobs/claim} request body (architecture §11 names only {@code backendId}; {@code
 * workerId} is added here as a deliberate spec-completion — {@code QueueManager#claim} requires a
 * worker identity to attribute the claim/heartbeat/result ownership chain (SR-04/SR-05), and the
 * architecture's own {@code HeartbeatRequest}/{@code SubmitResultRequest} both carry one, so claim
 * must too).
 */
public record ClaimJobRequest(@NotBlank String backendId, @NotBlank String workerId) {
}

package com.review.gateway.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /jobs/{id}/heartbeat} request body (architecture §11). */
public record HeartbeatRequest(@NotBlank String workerId) {
}

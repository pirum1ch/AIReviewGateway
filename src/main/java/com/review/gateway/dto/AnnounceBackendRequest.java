package com.review.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /backends/announce} request body (WORKER role, architecture §2.2). Field sizes match the
 * DB column widths so a violation is never a {@code 500}; charsets also rule out CR/LF log-injection
 * (same discipline as {@link ClaimJobRequest}). No {@code capacity} field — a Worker is structurally
 * capacity-1, so there is nothing for it to declare, and omitting it means an operator-set {@code
 * capacity} is never stomped by a restart (architecture §2.2).
 */
public record AnnounceBackendRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$") String backendId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$") String workerId,
        @NotBlank @Size(max = 256) String url,
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._:/-]{1,128}$") String model) {
}

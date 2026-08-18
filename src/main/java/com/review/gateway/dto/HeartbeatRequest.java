package com.review.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /jobs/{id}/heartbeat} request body (architecture §11).
 *
 * <p><b>WOR-06:</b> {@code @Size(max = 64)} matches the {@code VARCHAR(64)} column width and {@code
 * @Pattern} bounds the character set before this value is ever logged or persisted (CRLF/control-char
 * log-injection).
 */
public record HeartbeatRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]{1,64}$") String workerId) {
}

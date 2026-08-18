package com.review.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /jobs/{id}/fail} request body (architecture §5.2, WOC-33; WOR-06).
 *
 * <p>{@code reason} is a closed-set code, whitelist-parsed Gateway-side against {@link
 * com.review.gateway.model.enums.JobFailureReason} (WOC-23) — never {@code Enum.valueOf} on this raw
 * value, never a {@code 400} for an unrecognized one. {@code detail} is optional, untrusted free text,
 * sanitized and truncated to 200 chars <b>server-side, at ingress</b> (WOC-25/WOR-06c) before it can
 * reach a log line, {@code review_events}, or {@code review_jobs.last_error}; the {@code @Size(max=500)}
 * bound here is only the outer edge cap (JSON-escaping overhead considered), not the stored length.
 */
public record FailJobRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._:-]{1,64}$") String workerId,
        @NotBlank @Size(max = 32) String reason,
        @Size(max = 500) String detail) {
}

package com.review.gateway.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /backends} request body (ADMIN role, architecture §2.3) — upsert by {@code name}.
 * PATCH-like on update: every field besides {@code name} is optional, and an absent (or {@code null})
 * optional field leaves that column unchanged — a full-replace {@code PUT} would let an operator wipe
 * {@code structuredOutputMode}/{@code promptMessageFormat} mid-rollout by forgetting one field.
 * {@code url}/{@code model} are required on create only (checked in {@code BackendRegistryService}, not
 * here — bean validation cannot see whether the target row already exists).
 *
 * <p>{@code status} accepts {@code ACTIVE}/{@code MAINTENANCE}/{@code OFFLINE} only — {@code SUSPECT} is
 * health-checker-owned. {@code structuredOutputMode}/{@code promptMessageFormat} are bounded to the same
 * closed vocabularies as their DB {@code CHECK} constraints (V5/V3), so a typo fails cleanly here as
 * {@code 400 VALIDATION_ERROR} instead of surfacing as a {@code 500} from the constraint at flush time.
 */
public record UpsertBackendRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$") String name,
        @Size(max = 256) String url,
        @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9._:/-]{1,128}$") String model,
        @Min(1) @Max(64) Integer capacity,
        @Pattern(regexp = "ACTIVE|MAINTENANCE|OFFLINE") String status,
        @Pattern(regexp = "OFF|RESPONSE_FORMAT_JSON_SCHEMA|RESPONSE_FORMAT_SCHEMA|TOP_LEVEL_JSON_SCHEMA") String structuredOutputMode,
        @Pattern(regexp = "MULTI|SINGLE") String promptMessageFormat) {
}

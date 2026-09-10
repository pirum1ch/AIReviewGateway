package com.review.gateway.service.dto;

import com.review.gateway.model.Backend;

/**
 * Result of {@code BackendRegistryService#upsertByAdmin} (architecture §2.3). {@code created} is {@code
 * true} only for a fresh INSERT (drives the {@code 201} vs {@code 200} split in {@code AdminController}).
 * Carries the persisted {@link Backend} entity itself — never the request DTO — so the controller can
 * only ever build its response from durable, server-decided state (BSQ-17).
 */
public record BackendUpsertOutcome(Backend backend, boolean created) {
}

package com.review.gateway.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the Structured Review Output counters on {@link MetricsCounters} (SRO-45). */
class MetricsCountersTest {

    @Test
    void legacyParseFallbackCounts() {
        MetricsCounters counters = new MetricsCounters();

        counters.incrementLegacyParseFallback();
        counters.incrementLegacyParseFallback();

        assertThat(counters.legacyParseFallbackCount()).isEqualTo(2);
    }

    @Test
    void structuredValidationFailuresAreKeyedByKind() {
        MetricsCounters counters = new MetricsCounters();

        counters.incrementStructuredValidationFailure("NOT_JSON");
        counters.incrementStructuredValidationFailure("NOT_JSON");
        counters.incrementStructuredValidationFailure("COVERAGE_SHORTFALL");

        assertThat(counters.structuredValidationFailuresSnapshot())
                .containsEntry("NOT_JSON", 2L)
                .containsEntry("COVERAGE_SHORTFALL", 1L);
    }

    @Test
    void structuredConstraintSentIsKeyedByMode() {
        MetricsCounters counters = new MetricsCounters();

        counters.incrementStructuredConstraintSent("OFF");
        counters.incrementStructuredConstraintSent("TOP_LEVEL_JSON_SCHEMA");
        counters.incrementStructuredConstraintSent("OFF");

        assertThat(counters.structuredConstraintSentSnapshot())
                .containsEntry("OFF", 2L)
                .containsEntry("TOP_LEVEL_JSON_SCHEMA", 1L);
    }

    @Test
    void structuredFallbackUsedCounts() {
        MetricsCounters counters = new MetricsCounters();

        counters.incrementStructuredFallbackUsed();

        assertThat(counters.structuredFallbackUsedCount()).isEqualTo(1);
    }

    @Test
    void everyNewCounterStartsAtZero() {
        MetricsCounters counters = new MetricsCounters();

        assertThat(counters.legacyParseFallbackCount()).isZero();
        assertThat(counters.structuredValidationFailuresSnapshot()).isEmpty();
        assertThat(counters.structuredConstraintSentSnapshot()).isEmpty();
        assertThat(counters.structuredFallbackUsedCount()).isZero();
    }
}

package com.review.gateway.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link FinishReason} (SRO-42/43/44). */
class FinishReasonTest {

    @Test
    void recognizesEveryWireValueCaseInsensitively() {
        assertThat(FinishReason.fromWireValue("stop")).isEqualTo(FinishReason.STOP);
        assertThat(FinishReason.fromWireValue("LENGTH")).isEqualTo(FinishReason.LENGTH);
        assertThat(FinishReason.fromWireValue("Content_Filter")).isEqualTo(FinishReason.CONTENT_FILTER);
        assertThat(FinishReason.fromWireValue("tool_calls")).isEqualTo(FinishReason.TOOL_CALLS);
    }

    @Test
    void nullAndUnrecognizedValuesMapToUnknownNeverThrow() {
        assertThat(FinishReason.fromWireValue(null)).isEqualTo(FinishReason.UNKNOWN);
        assertThat(FinishReason.fromWireValue("")).isEqualTo(FinishReason.UNKNOWN);
        assertThat(FinishReason.fromWireValue("some-future-value")).isEqualTo(FinishReason.UNKNOWN);
    }

    @Test
    void wireValueRoundTripsToLowercase() {
        assertThat(FinishReason.LENGTH.wireValue()).isEqualTo("length");
        assertThat(FinishReason.CONTENT_FILTER.wireValue()).isEqualTo("content_filter");
        assertThat(FinishReason.UNKNOWN.wireValue()).isEqualTo("unknown");
    }
}

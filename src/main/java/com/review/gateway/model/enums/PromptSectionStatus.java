package com.review.gateway.model.enums;

/**
 * Whether a {@link com.review.gateway.model.ReviewPromptSection} row actually holds content
 * ({@code PRESENT}) or records that a configured/optional source was looked up and found absent
 * ({@code ABSENT} — PMR-11: a positive "we looked, it was not there" audit row, never silence).
 */
public enum PromptSectionStatus {
    PRESENT,
    ABSENT
}

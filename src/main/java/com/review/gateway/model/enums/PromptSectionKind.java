package com.review.gateway.model.enums;

/**
 * Which piece of the assembled system prompt a {@link com.review.gateway.model.ReviewPromptSection}
 * row is (architecture §4/§6). {@code CORPORATE_*} sections are mandatory, org-wide, and trusted by
 * policy; {@code PROJECT_*} sections are optional, per-project, and untrusted (PMTB-PROJ) — the two
 * groups are never mergeable (PMR-04): the DB {@code CHECK} constraint on {@code kind} plus this being
 * a closed enum is what makes "a project-sourced row labelled corporate" structurally impossible, not
 * just conventionally avoided.
 */
public enum PromptSectionKind {
    CORPORATE_BASE,
    CORPORATE_REVIEW_RULES,
    PROJECT_ARCHITECTURE,
    PROJECT_CODE_RULES
}

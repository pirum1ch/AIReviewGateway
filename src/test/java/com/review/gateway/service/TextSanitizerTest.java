package com.review.gateway.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared {@link TextSanitizer} (PMR-03, generalized from
 * {@code ChunkContextRenderer.sanitizePath}). Uses explicit {@code \\uXXXX} escapes throughout (rather
 * than literal glyphs) so the exact code points under test are unambiguous regardless of editor/terminal
 * encoding.
 */
class TextSanitizerTest {

    private static final String BIDI_OVERRIDE = "‮"; // RIGHT-TO-LEFT OVERRIDE (Trojan Source)
    private static final String BIDI_POP = "⁩"; // POP DIRECTIONAL ISOLATE
    private static final String DELIMITER_CHAR = "␞"; // SYMBOL FOR RECORD SEPARATOR

    private final TextSanitizer sanitizer = new TextSanitizer();

    @Test
    void sanitizePathStripsControlAndFormatCharacters() {
        String withControls = "src/A.java" + BIDI_OVERRIDE + "malicious";
        String result = sanitizer.sanitizePath(withControls, 300);
        assertThat(result).doesNotContain(BIDI_OVERRIDE);
    }

    @Test
    void sanitizePathStripsNewlinesAndTabs() {
        assertThat(sanitizer.sanitizePath("a\nb\tc", 300)).isEqualTo("abc");
    }

    @Test
    void sanitizePathStripsAngleBrackets() {
        assertThat(sanitizer.sanitizePath("<<<INJECT>>>", 300)).isEqualTo("INJECT");
    }

    @Test
    void sanitizePathReturnsNullWhenNothingRemains() {
        assertThat(sanitizer.sanitizePath("", 300)).isNull();
    }

    @Test
    void sanitizeSectionTextPreservesNewlinesAndTabs() {
        String text = "line1\nline2\tindented";
        assertThat(sanitizer.sanitizeSectionText(text)).isEqualTo(text);
    }

    @Test
    void sanitizeSectionTextStripsBidiOverrideCharacters() {
        String text = "safe " + BIDI_OVERRIDE + "nasty" + BIDI_POP + " text";
        String result = sanitizer.sanitizeSectionText(text);
        assertThat(result).doesNotContain(BIDI_OVERRIDE).doesNotContain(BIDI_POP);
    }

    @Test
    void sanitizeSectionTextStripsDelimiterCharacter() {
        String text = "some " + DELIMITER_CHAR + DELIMITER_CHAR + DELIMITER_CHAR + " BEGIN X " + DELIMITER_CHAR
                + DELIMITER_CHAR + DELIMITER_CHAR + " prose";
        String result = sanitizer.sanitizeSectionText(text);
        assertThat(result).doesNotContain(DELIMITER_CHAR);
    }

    @Test
    void sanitizeSectionTextSelfNestingPayloadCannotReconstructTheDelimiterCharacter() {
        // F-DC-02 lesson applied to the new delimiter alphabet: every occurrence of the delimiter code
        // point is stripped as a character class (not a single-pass String.replace of a multi-char
        // token), so no concatenation of "sanitized" fragments can ever reconstruct it.
        String crafted = "prefix" + DELIMITER_CHAR + "middle" + DELIMITER_CHAR + "suffix";
        String result = sanitizer.sanitizeSectionText(crafted);
        assertThat(result).doesNotContain(DELIMITER_CHAR);
        assertThat(result).isEqualTo("prefixmiddlesuffix");
    }

    @Test
    void sanitizeSectionTextRejectsNothingEmptyStringStaysEmpty() {
        assertThat(sanitizer.sanitizeSectionText("")).isEmpty();
    }

    @Test
    void sanitizeSectionTextKeepsOrdinaryProse() {
        String prose = "This project uses hexagonal architecture with clear boundaries.";
        assertThat(sanitizer.sanitizeSectionText(prose)).isEqualTo(prose);
    }

    @Test
    void ccStrippingRunsBeforeDelimiterStrippingOrderIsIrrelevantSinceBothAreFullyStripped() {
        // CSR-09 ordering note: since both passes fully strip their character classes (not
        // find-replace of a reconstructible token), the two passes commute -- this test only pins the
        // observable outcome (both classes gone), matching TextSanitizer's documented contract.
        String text = BIDI_OVERRIDE + DELIMITER_CHAR + "text" + DELIMITER_CHAR + BIDI_OVERRIDE;
        String result = sanitizer.sanitizeSectionText(text);
        assertThat(result).isEqualTo("text");
    }

    // WOC-25/WOR-06: sanitizeSingleLine is a delegating alias for sanitizePath (no second implementation).
    @Test
    void sanitizeSingleLineDelegatesToSanitizePathByteForByte() {
        String raw = "line1\r\n2026-01-01 INFO forged" + BIDI_OVERRIDE + "<script>";
        assertThat(sanitizer.sanitizeSingleLine(raw, 200)).isEqualTo(sanitizer.sanitizePath(raw, 200));
    }

    @Test
    void sanitizeSingleLineStripsCrlfAndCapsLength() {
        String raw = "a\r\nb" + "x".repeat(300);
        String result = sanitizer.sanitizeSingleLine(raw, 200);
        assertThat(result).doesNotContain("\r").doesNotContain("\n");
        assertThat(result.length()).isLessThanOrEqualTo(200);
    }

    @Test
    void sanitizeSingleLineReturnsNullForNullInput() {
        assertThat(sanitizer.sanitizeSingleLine(null, 200)).isNull();
    }
}

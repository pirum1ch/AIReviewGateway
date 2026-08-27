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

    // ---- truncateSafely (SGB-03/SOGB-03, Structured Output Grammar Budget) ----
    //
    // QA round: no dedicated unit test for this helper existed before (only indirect coverage via
    // StructuredResponseParserTest). Pins the exact boundary behavior directly, including the one-off
    // cases an off-by-one in a hand-rolled cut is most likely to hide in.

    @Test
    void truncateSafelyReturnsUnchangedAndNotTruncatedWhenTextIsShorterThanCap() {
        TextSanitizer.Truncation result = sanitizer.truncateSafely("short", 100);
        assertThat(result.truncated()).isFalse();
        assertThat(result.text()).isEqualTo("short");
    }

    @Test
    void truncateSafelyAtExactlyTheCapIsNotTruncated() {
        String text = "x".repeat(10);
        TextSanitizer.Truncation result = sanitizer.truncateSafely(text, 10);
        assertThat(result.truncated()).as("a value exactly at the cap must never be reported as truncated (no false positive)").isFalse();
        assertThat(result.text()).isEqualTo(text);
    }

    @Test
    void truncateSafelyOneCharOverTheCapIsTruncatedToExactlyTheCap() {
        String text = "x".repeat(11);
        TextSanitizer.Truncation result = sanitizer.truncateSafely(text, 10);
        assertThat(result.truncated()).isTrue();
        assertThat(result.text()).hasSize(10).isEqualTo("x".repeat(10));
    }

    @Test
    void truncateSafelyOneCharUnderTheCapIsNotTruncated() {
        String text = "x".repeat(9);
        TextSanitizer.Truncation result = sanitizer.truncateSafely(text, 10);
        assertThat(result.truncated()).isFalse();
        assertThat(result.text()).isEqualTo(text);
    }

    @Test
    void truncateSafelyBacksOffByOneWhenTheNaiveCutWouldSplitASurrogatePair() {
        String emoji = "😀"; // U+1F600, high+low surrogate pair
        String text = "x".repeat(9) + emoji + "tail"; // naive cut at 10 lands exactly on the low surrogate
        TextSanitizer.Truncation result = sanitizer.truncateSafely(text, 10);

        assertThat(result.truncated()).isTrue();
        // Backed off to 9 chars -- the whole surrogate pair is dropped rather than split.
        assertThat(result.text()).isEqualTo("x".repeat(9));
        assertThat(result.text().codePoints().toArray()).doesNotContain((int) '\uD83D');
    }

    @Test
    void truncateSafelyCutJustAfterACompleteSurrogatePairIsUnaffected() {
        String emoji = "😀";
        String text = "x".repeat(8) + emoji; // cap=10 lands exactly after the complete pair -- no backoff needed
        TextSanitizer.Truncation result = sanitizer.truncateSafely(text, 10);

        assertThat(result.truncated()).isFalse();
        assertThat(result.text()).isEqualTo(text);
    }

    @Test
    void truncateSafelyWithCapZeroReturnsEmptyStringNeverThrows() {
        TextSanitizer.Truncation result = sanitizer.truncateSafely("abc", 0);
        assertThat(result.truncated()).isTrue();
        assertThat(result.text()).isEmpty();
    }

    @Test
    void truncateSafelyWithNegativeCapIsTreatedAsZero() {
        TextSanitizer.Truncation result = sanitizer.truncateSafely("abc", -5);
        assertThat(result.truncated()).isTrue();
        assertThat(result.text()).isEmpty();
    }

    @Test
    void truncateSafelyReturnsNullTextUnchangedForNullInput() {
        TextSanitizer.Truncation result = sanitizer.truncateSafely(null, 100);
        assertThat(result.truncated()).isFalse();
        assertThat(result.text()).isNull();
    }

    @Test
    void truncateSafelyEmptyStringIsNeverTruncated() {
        TextSanitizer.Truncation result = sanitizer.truncateSafely("", 10);
        assertThat(result.truncated()).isFalse();
        assertThat(result.text()).isEmpty();
    }
}

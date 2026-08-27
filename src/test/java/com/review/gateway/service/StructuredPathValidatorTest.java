package com.review.gateway.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StructuredPathValidator} (architecture SRO-65, threat model SOR-01, BLOCKING).
 */
class StructuredPathValidatorTest {

    private final StructuredPathValidator validator = new StructuredPathValidator();

    @Test
    void acceptsAnOrdinaryRelativePath() {
        assertThat(validator.isEligible("src/main/java/com/review/gateway/service/DiffChunker.java", 256)).isTrue();
    }

    @Test
    void rejectsNullOrEmpty() {
        assertThat(validator.isEligible(null, 256)).isFalse();
        assertThat(validator.isEligible("", 256)).isFalse();
    }

    @Test
    void rejectsPathsLongerThanMaxPathChars() {
        String longPath = "a/".repeat(200) + "File.java";
        assertThat(validator.isEligible(longPath, 10)).isFalse();
        assertThat(validator.isEligible("short.java", 10)).isTrue();
    }

    @Test
    void rejectsLeadingSlash() {
        assertThat(validator.isEligible("/etc/passwd", 256)).isFalse();
    }

    @Test
    void rejectsADotDotSegment() {
        assertThat(validator.isEligible("../../etc/passwd", 256)).isFalse();
        assertThat(validator.isEligible("a/../b.java", 256)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a{b.java", "a}b.java", "a\"b.java", "a\\b.java", "a`b.java",
            "a[b.java", "a]b.java", "a|b.java", "a*b.java"
    })
    void rejectsEachForbiddenCharacter(String path) {
        assertThat(validator.isEligible(path, 256)).isFalse();
    }

    @Test
    void rejectsWhitespaceIncludingNonAsciiSpace() {
        assertThat(validator.isEligible("a b.java", 256)).isFalse();
        assertThat(validator.isEligible("a\tb.java", 256)).isFalse();
        // U+00A0 NO-BREAK SPACE -- a Unicode space character, not matched by \s in some engines.
        assertThat(validator.isEligible("a b.java", 256)).isFalse();
    }

    @Test
    void closesThePromptSchemaDivergenceViaTheCurlyBraceRejection() {
        // SOT-02: a path shown as "DIFFHelper.java" (after the Worker's {{ }} strip) but required as
        // "{{DIFF}}Helper.java" in the schema -- rejected outright rather than silently mismatched.
        assertThat(validator.isEligible("{{DIFF}}Helper.java", 256)).isFalse();
    }

    @Test
    void closesTheMarkdownHeaderBreakoutViaTheBacktickRejection() {
        assertThat(validator.isEligible("a`.java", 256)).isFalse();
    }

    @Test
    void closesTheGitQuotePathMangling() {
        // core.quotePath=true mangling produces backslashes and a trailing quote.
        assertThat(validator.isEligible("\\303\\251.java\"", 256)).isFalse();
    }

    @Test
    void acceptsAPathAtExactlyTheMaxLength() {
        String path = "a".repeat(256);
        assertThat(validator.isEligible(path, 256)).isTrue();
        assertThat(validator.isEligible(path + "a", 256)).isFalse();
    }
}

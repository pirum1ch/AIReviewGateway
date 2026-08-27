package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.enums.Severity;
import com.review.gateway.service.StructuredResponseParser.FailureKind;
import com.review.gateway.service.StructuredResponseParser.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link StructuredResponseParser} (architecture §5.1, SRO-30-34; threat model SOR-11
 * CRITICAL, SOR-14 TRACKED).
 */
class StructuredResponseParserTest {

    private final GatewayProperties properties = new GatewayProperties();

    private final MetricsCounters metricsCounters = new MetricsCounters();

    private StructuredResponseParser newParser() {
        CommentParser commentParser = new CommentParser(properties, new MetricsCounters());
        CommentRenderer commentRenderer = new CommentRenderer(commentParser, new TextSanitizer(), properties);
        return new StructuredResponseParser(commentParser, commentRenderer, new TextSanitizer(), properties, metricsCounters);
    }

    private ReviewSchemaBuilder.SchemaOptions defaultOptions() {
        return new ReviewSchemaBuilder.SchemaOptions(20, 1200, 2000, true);
    }

    /** F-SRO-04: a generous cap so pre-existing tests (which assert exact comment counts well under this) are unaffected. */
    private static final int NO_EFFECTIVE_CAP = 1_000;

    private ValidationResult validate(String raw, List<String> expectedPaths) {
        return newParser().validate(raw, expectedPaths, false, null, null, defaultOptions(), NO_EFFECTIVE_CAP);
    }

    // ---- SRO-67c: the empty-expected-set invariant ----

    @Test
    void emptyExpectedPathsThrowsRatherThanValidating() {
        StructuredResponseParser parser = newParser();

        assertThatThrownBy(() -> parser.validate("{}", List.of(), false, null, null, defaultOptions(), NO_EFFECTIVE_CAP))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parser.validate("{}", null, false, null, null, defaultOptions(), NO_EFFECTIVE_CAP))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- success path ----

    @Test
    void aConformingResponseValidatesSuccessfullyAndRendersComments() {
        String raw = """
                {"files":{"A.java":{"findings":[{"line":3,"severity":"major","comment":"Bug here","suggestion":""}],"summary":"ok"}},"summary":"overall"}
                """;

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.success().summary()).isEqualTo("overall");
        assertThat(result.success().comments()).hasSize(1);
        assertThat(result.success().comments().get(0).severity()).isEqualTo(Severity.MAJOR);
        assertThat(result.success().comments().get(0).lineNumber()).isEqualTo(3);
        assertThat(result.success().comments().get(0).text()).contains("Bug here");
    }

    @Test
    void aFileWithEmptyFindingsProducesNoComments() {
        String raw = """
                {"files":{"A.java":{"findings":[],"summary":"clean"}},"summary":"overall"}
                """;

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.success().comments()).isEmpty();
    }

    @Test
    void lineZeroIsNormalizedToNullFileLevelFinding() {
        String raw = """
                {"files":{"A.java":{"findings":[{"line":0,"severity":"info","comment":"file level","suggestion":""}],"summary":"s"}},"summary":"overall"}
                """;

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.success().comments().get(0).lineNumber()).isNull();
    }

    @Test
    void aDuplicateEntryInExpectedPathsNeverDoublesPublishedComments() {
        // F-SRO-02 belt-and-braces: the primary defense against a duplicate expectedPaths entry is
        // ReviewService's SRO-17 collision check (ReviewServiceTest), but this class must independently
        // never process the same file twice even if a duplicate ever reached it -- it iterates the
        // deduped expectedSet, not the raw expectedPaths list.
        String raw = """
                {"files":{"A.java":{"findings":[{"line":3,"severity":"major","comment":"Bug here","suggestion":""}],"summary":"ok"}},"summary":"overall"}
                """;

        ValidationResult result = validate(raw, List.of("A.java", "A.java"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.success().comments())
                .as("a duplicate entry in expectedPaths must never cause a file's findings to be collected/"
                        + "rendered twice")
                .hasSize(1);
    }

    @Test
    void perFileSummaryDisabledDoesNotRequireTheSummaryField() {
        String raw = """
                {"files":{"A.java":{"findings":[]}},"summary":"overall"}
                """;
        StructuredResponseParser parser = newParser();
        ReviewSchemaBuilder.SchemaOptions options = new ReviewSchemaBuilder.SchemaOptions(20, 1200, 2000, false);

        ValidationResult result = parser.validate(raw, List.of("A.java"), false, null, null, options, NO_EFFECTIVE_CAP);

        assertThat(result.isSuccess()).isTrue();
    }

    // ---- NOT_JSON ----

    @Test
    void malformedJsonIsClassifiedNotJson() {
        ValidationResult result = validate("not json at all {{{", List.of("A.java"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.failure().kind()).isEqualTo(FailureKind.NOT_JSON);
    }

    @Test
    void aJsonArrayRootIsClassifiedNotJson() {
        ValidationResult result = validate("[1,2,3]", List.of("A.java"));

        assertThat(result.failure().kind()).isEqualTo(FailureKind.NOT_JSON);
    }

    @Test
    void markdownFencedJsonIsNeverToleratedNoFallbackScan() {
        // SRO-31: no markdown-fence stripping, no prose tolerance -- this must fail, not be salvaged.
        String raw = "```json\n{\"files\":{},\"summary\":\"x\"}\n```";

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.isSuccess()).isFalse();
    }

    // ---- TRUNCATED, checked before NOT_JSON ----

    @Test
    void finishReasonLengthIsClassifiedTruncatedRegardlessOfContent() {
        StructuredResponseParser parser = newParser();

        ValidationResult result = parser.validate("{}", List.of("A.java"), false, "length", null, defaultOptions(), NO_EFFECTIVE_CAP);

        assertThat(result.failure().kind()).isEqualTo(FailureKind.TRUNCATED);
    }

    @Test
    void finishReasonLengthIsClassifiedTruncatedRegardlessOfCaseOrWhitespace() {
        // F-SRO-08(a): must compare the whitelist-PARSED value (FinishReason.fromWireValue: trim +
        // lowercase), not the raw wire string -- the value actually STORED in review_results.finish_reason
        // goes through that same parse, so a differently-cased/padded wire value must classify identically
        // to "length" here, or the DB row and the classification silently disagree.
        StructuredResponseParser parser = newParser();

        for (String raw : new String[] {"Length", "LENGTH", " length", "length "}) {
            ValidationResult result = parser.validate("{}", List.of("A.java"), false, raw, null, defaultOptions(), NO_EFFECTIVE_CAP);
            assertThat(result.failure().kind()).as("finishReason=%s", raw).isEqualTo(FailureKind.TRUNCATED);
        }
    }

    @Test
    void finishReasonStopIsNotClassifiedTruncated() {
        String raw = "{\"files\":{\"A.java\":{\"findings\":[],\"summary\":\"s\"}},\"summary\":\"overall\"}";
        StructuredResponseParser parser = newParser();

        ValidationResult result = parser.validate(raw, List.of("A.java"), false, "stop", null, defaultOptions(), NO_EFFECTIVE_CAP);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void rawResponseTruncatedFlagIsClassifiedTruncated() {
        StructuredResponseParser parser = newParser();

        ValidationResult result = parser.validate("{}", List.of("A.java"), true, null, null, defaultOptions(), NO_EFFECTIVE_CAP);

        assertThat(result.failure().kind()).isEqualTo(FailureKind.TRUNCATED);
    }

    @Test
    void unexpectedEndOfInputIsClassifiedTruncatedNotNotJson() {
        String raw = "{\"files\":{\"A.java\":{\"findings\":[{\"line\":1,\"sev";

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.failure().kind()).isEqualTo(FailureKind.TRUNCATED);
    }

    // ---- SCHEMA_MISMATCH ----

    @Test
    void missingTopLevelKeyIsSchemaMismatch() {
        ValidationResult result = validate("{\"files\":{}}", List.of("A.java"));

        assertThat(result.failure().kind()).isEqualTo(FailureKind.SCHEMA_MISMATCH);
    }

    @Test
    void extraTopLevelKeyIsSchemaMismatch() {
        String raw = "{\"files\":{},\"summary\":\"x\",\"extra\":1}";

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.failure().kind()).isEqualTo(FailureKind.SCHEMA_MISMATCH);
    }

    @Test
    void filesNotAnObjectIsSchemaMismatch() {
        String raw = "{\"files\":[],\"summary\":\"x\"}";

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.failure().kind()).isEqualTo(FailureKind.SCHEMA_MISMATCH);
    }

    @Test
    void fileEntryMissingFindingsIsSchemaMismatch() {
        String raw = "{\"files\":{\"A.java\":{\"summary\":\"x\"}},\"summary\":\"y\"}";

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.failure().kind()).isEqualTo(FailureKind.SCHEMA_MISMATCH);
    }

    @Test
    void findingMissingARequiredFieldIsSchemaMismatch() {
        String raw = """
                {"files":{"A.java":{"findings":[{"line":1,"severity":"major","comment":"x"}],"summary":"s"}},"summary":"y"}
                """;

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.failure().kind()).isEqualTo(FailureKind.SCHEMA_MISMATCH);
    }

    @Test
    void findingWithWrongJsonTypeIsSchemaMismatch() {
        String raw = """
                {"files":{"A.java":{"findings":[{"line":"not-a-number","severity":"major","comment":"x","suggestion":""}],"summary":"s"}},"summary":"y"}
                """;

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.failure().kind()).isEqualTo(FailureKind.SCHEMA_MISMATCH);
    }

    @Test
    void severityOutsideTheEnumIsSchemaMismatchNeverSilentlyDowngraded() {
        String raw = """
                {"files":{"A.java":{"findings":[{"line":1,"severity":"URGENT","comment":"x","suggestion":""}],"summary":"s"}},"summary":"y"}
                """;

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.failure().kind()).isEqualTo(FailureKind.SCHEMA_MISMATCH);
    }

    @Test
    void findingsArrayExceedingMaxFindingsPerFileIsSchemaMismatch() {
        StringBuilder findings = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                findings.append(',');
            }
            findings.append("{\"line\":1,\"severity\":\"info\",\"comment\":\"x\",\"suggestion\":\"\"}");
        }
        String raw = "{\"files\":{\"A.java\":{\"findings\":[" + findings + "],\"summary\":\"s\"}},\"summary\":\"y\"}";
        StructuredResponseParser parser = newParser();
        ReviewSchemaBuilder.SchemaOptions tightOptions = new ReviewSchemaBuilder.SchemaOptions(2, 1200, 2000, true);

        ValidationResult result = parser.validate(raw, List.of("A.java"), false, null, null, tightOptions, NO_EFFECTIVE_CAP);

        assertThat(result.failure().kind()).isEqualTo(FailureKind.SCHEMA_MISMATCH);
    }

    // ---- SGB-03/SGB-04/T-4.11 (Structured Output Grammar Budget): truncate, never reject ----
    //
    // SGB-01 removed maxLength from the emitted schema (a bounded string repetition is what tripped
    // llama.cpp's grammar-parser complexity guard in production). An over-length comment/suggestion is
    // therefore now expected, routine shape -- truncated to the configured cap and published, never a
    // whole-chunk-retryable SCHEMA_MISMATCH (SRO-04's re-check is still unconditional, it just no longer
    // rejects on this one dimension).

    @Test
    void commentExceedingTheConfiguredCapIsTruncatedAndPublishedNeverSchemaMismatch() {
        String longComment = "x".repeat(50);
        String raw = "{\"files\":{\"A.java\":{\"findings\":[{\"line\":1,\"severity\":\"info\",\"comment\":\""
                + longComment + "\",\"suggestion\":\"\"}],\"summary\":\"s\"}},\"summary\":\"y\"}";
        StructuredResponseParser parser = newParser();
        ReviewSchemaBuilder.SchemaOptions tightOptions = new ReviewSchemaBuilder.SchemaOptions(20, 10, 2000, true);

        ValidationResult result = parser.validate(raw, List.of("A.java"), false, null, null, tightOptions, NO_EFFECTIVE_CAP);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.success().comments()).hasSize(1);
        String published = result.success().comments().get(0).text();
        // Truncated to the 10-char cap, not the full 50 "x"s.
        assertThat(published).doesNotContain("x".repeat(11));
    }

    /**
     * T-4.11 / SOGB-01 (BLOCKING): the truncated SUGGESTION must still carry {@code ALTERED_CODE_MARKER}
     * -- the SOGT-01 regression this branch closes is exactly a pre-truncated string silently reaching
     * {@code CommentRenderer} and coming back {@code altered == false}.
     */
    @Test
    void overLengthSuggestionIsTruncatedAndStillCarriesTheAlteredCodeMarker() {
        String longSuggestion = "int x = 1;\n".repeat(10); // well over a small configured cap
        String raw = "{\"files\":{\"A.java\":{\"findings\":[{\"line\":1,\"severity\":\"info\",\"comment\":\"c\","
                + "\"suggestion\":\"" + longSuggestion.replace("\n", "\\n") + "\"}],\"summary\":\"s\"}},\"summary\":\"y\"}";
        StructuredResponseParser parser = newParser();
        ReviewSchemaBuilder.SchemaOptions tightOptions = new ReviewSchemaBuilder.SchemaOptions(20, 1200, 20, true);

        ValidationResult result = parser.validate(raw, List.of("A.java"), false, null, null, tightOptions, NO_EFFECTIVE_CAP);

        assertThat(result.isSuccess()).isTrue();
        String published = result.success().comments().get(0).text();
        assertThat(published).as("a truncated suggestion must never read as a verbatim quotation")
                .contains("_(normalized for display; not a verbatim quotation)_");
    }

    @Test
    void overLengthCommentIsTruncatedAndEndsInTheConstantTruncatedMarker() {
        String longComment = "This is a very long comment that will not fit.";
        String raw = "{\"files\":{\"A.java\":{\"findings\":[{\"line\":1,\"severity\":\"info\",\"comment\":\""
                + longComment + "\",\"suggestion\":\"\"}],\"summary\":\"s\"}},\"summary\":\"y\"}";
        StructuredResponseParser parser = newParser();
        ReviewSchemaBuilder.SchemaOptions tightOptions = new ReviewSchemaBuilder.SchemaOptions(20, 12, 2000, true);

        ValidationResult result = parser.validate(raw, List.of("A.java"), false, null, null, tightOptions, NO_EFFECTIVE_CAP);

        assertThat(result.isSuccess()).isTrue();
        String published = result.success().comments().get(0).text();
        assertThat(published.stripTrailing()).as("prose truncated by SGB-03 must end in the constant marker")
                .endsWith("_(truncated)_");
    }

    @Test
    void incrementsTheStructuredFieldTruncatedCounter() {
        String raw = "{\"files\":{\"A.java\":{\"findings\":[{\"line\":1,\"severity\":\"info\",\"comment\":\""
                + "x".repeat(50) + "\",\"suggestion\":\"" + "y".repeat(50) + "\"}],\"summary\":\"s\"}},\"summary\":\"y\"}";
        StructuredResponseParser parser = newParser();
        ReviewSchemaBuilder.SchemaOptions tightOptions = new ReviewSchemaBuilder.SchemaOptions(20, 10, 10, true);

        parser.validate(raw, List.of("A.java"), false, null, null, tightOptions, NO_EFFECTIVE_CAP);

        assertThat(metricsCounters.structuredFieldTruncatedSnapshot())
                .containsEntry("comment", 1L)
                .containsEntry("suggestion", 1L);
    }

    /**
     * SOGB-03: the truncation point must fall safely outside a backtick run -- a cut landing mid-run
     * (e.g. after two of a four-backtick run) must not leave a partial fence-shaped sequence in the
     * published body, since {@code sanitizeCodeBlock}'s own backtick-run collapse always runs on
     * whatever remains AFTER this truncation (SGB-04's load-bearing ordering).
     */
    @Test
    void truncationLandingInsideABacktickRunStillProducesAFenceBalancedBody() {
        // cap=10: "code here" (9 chars) + "````" (4 backticks) truncates to "code here" + "`" (cap=10) --
        // landing one backtick INTO what was a 4-backtick run in the raw model output.
        String rawSuggestion = "code here````moretext";
        String raw = "{\"files\":{\"A.java\":{\"findings\":[{\"line\":1,\"severity\":\"info\",\"comment\":\"c\","
                + "\"suggestion\":\"" + rawSuggestion + "\"}],\"summary\":\"s\"}},\"summary\":\"y\"}";
        StructuredResponseParser parser = newParser();
        ReviewSchemaBuilder.SchemaOptions tightOptions = new ReviewSchemaBuilder.SchemaOptions(20, 1200, 10, true);

        ValidationResult result = parser.validate(raw, List.of("A.java"), false, null, null, tightOptions, NO_EFFECTIVE_CAP);

        assertThat(result.isSuccess()).isTrue();
        String published = result.success().comments().get(0).text();
        long fenceCount = countOccurrences(published, "````");
        assertThat(fenceCount % 2).as("the Gateway's own four-backtick fence marker must still pair up").isEqualTo(0);
    }

    /**
     * SOGB-03/SOGT-02 (CRITICAL): a cut that would otherwise land exactly between a UTF-16 high/low
     * surrogate pair must back off by one char instead -- never produce a String with a lone surrogate,
     * which Jackson's UTF-8 writer/the JDBC driver are not obliged to accept.
     */
    @Test
    void truncationAtASurrogatePairBoundaryNeverProducesALoneSurrogate() {
        String emoji = "😀"; // U+1F600, a two-char UTF-16 surrogate pair
        // cap=10: 9 ASCII chars + the emoji -- the naive cut point (index 10) lands exactly on the low
        // surrogate half of the pair.
        String rawComment = "x".repeat(9) + emoji + "tail";
        String raw = "{\"files\":{\"A.java\":{\"findings\":[{\"line\":1,\"severity\":\"info\",\"comment\":\""
                + rawComment + "\",\"suggestion\":\"\"}],\"summary\":\"s\"}},\"summary\":\"y\"}";
        StructuredResponseParser parser = newParser();
        ReviewSchemaBuilder.SchemaOptions tightOptions = new ReviewSchemaBuilder.SchemaOptions(20, 10, 2000, true);

        ValidationResult result = parser.validate(raw, List.of("A.java"), false, null, null, tightOptions, NO_EFFECTIVE_CAP);

        assertThat(result.isSuccess()).isTrue();
        String published = result.success().comments().get(0).text();
        assertThat(isEncodableUtf8(published)).as("must never contain a lone (unpaired) surrogate").isTrue();
        // Round-trips through Jackson without throwing.
        assertThatCode(() -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(published))
                .doesNotThrowAnyException();
    }

    private long countOccurrences(String haystack, String needle) {
        long count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private boolean isEncodableUtf8(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    // ---- COVERAGE_SHORTFALL ----

    @Test
    void missingAFileIsCoverageShortfall() {
        String raw = "{\"files\":{\"A.java\":{\"findings\":[],\"summary\":\"s\"}},\"summary\":\"y\"}";

        ValidationResult result = validate(raw, List.of("A.java", "B.java"));

        assertThat(result.failure().kind()).isEqualTo(FailureKind.COVERAGE_SHORTFALL);
        assertThat(result.failure().detail()).contains("B.java");
    }

    @Test
    void anUnexpectedExtraFileIsCoverageShortfall() {
        String raw = "{\"files\":{\"A.java\":{\"findings\":[],\"summary\":\"s\"},"
                + "\"Z.java\":{\"findings\":[],\"summary\":\"s\"}},\"summary\":\"y\"}";

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.failure().kind()).isEqualTo(FailureKind.COVERAGE_SHORTFALL);
        assertThat(result.failure().detail()).contains("Z.java");
    }

    @Test
    void coverageShortfallDetailCapsTheNumberOfListedKeys() {
        StringBuilder filesJson = new StringBuilder();
        List<String> expected = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String name = "File" + i + ".java";
            expected.add(name);
        }
        // Response covers none of the 20 expected files -- all 20 are "missing".
        String raw = "{\"files\":{},\"summary\":\"y\"}";

        ValidationResult result = validate(raw, expected);

        assertThat(result.failure().kind()).isEqualTo(FailureKind.COVERAGE_SHORTFALL);
        assertThat(result.failure().detail()).contains("more)");
    }

    // ---- SOR-11: structurally un-shortcuttable -- no Backend/StructuredOutputMode dependency ----

    /**
     * SOGB-04 (Structured Output Grammar Budget, threat model): tightened from a deny-list ("no Backend/
     * StructuredOutputMode field") to an allow-list -- the parser gained {@code MetricsCounters} as a
     * collaborator in this branch (its first new collaborator since SOR-11 was written), so the next one
     * added must fail this test until someone deliberately justifies it, rather than being silently
     * permitted by a deny-list that never mentioned it.
     */
    @Test
    void classOnlyReferencesItsApprovedCollaboratorSet() {
        List<String> allowedTypePrefixes = List.of(
                "com.review.gateway.service.CommentParser",
                "com.review.gateway.service.CommentRenderer",
                "com.review.gateway.service.TextSanitizer",
                "com.review.gateway.service.MetricsCounters",
                "com.review.gateway.config.GatewayProperties",
                "com.review.gateway.model.enums.",
                "com.review.gateway.service.dto.",
                "com.fasterxml.jackson.",
                "org.slf4j.Logger",
                "java.util.Set",
                "java.lang.String");
        for (var field : StructuredResponseParser.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            String typeName = type.getName();
            boolean allowed = type.isPrimitive()
                    || allowedTypePrefixes.stream().anyMatch(typeName::startsWith);
            assertThat(allowed)
                    .as("StructuredResponseParser.%s (type %s) is not on the SOR-11/SOGB-04 allow-list -- "
                            + "adding a new collaborator here needs a deliberate justification, since this "
                            + "class's un-shortcuttable validation is the property SOR-11 protects",
                            field.getName(), typeName)
                    .isTrue();
        }
    }

    @Test
    void aConformingResponseValidatesIdenticallyRegardlessOfAnyNotionOfBackendMode() {
        // There is no mode parameter on validate() at all -- this test documents that identity directly.
        String raw = """
                {"files":{"A.java":{"findings":[],"summary":"s"}},"summary":"y"}
                """;

        ValidationResult first = validate(raw, List.of("A.java"));
        ValidationResult second = validate(raw, List.of("A.java"));

        assertThat(first.isSuccess()).isTrue();
        assertThat(second.isSuccess()).isTrue();
    }

    // ---- F-SRO-01 (appsec SAST fix round): STRICT_DUPLICATE_DETECTION is now enabled on this parser's
    // ObjectMapper. These two fixtures used to pin down a silent last-write-wins data-loss bug (see git
    // history for the pre-fix versions of these tests); they are now inverted to assert that a duplicate
    // key is rejected outright as FailureKind.NOT_JSON, never silently resolved.

    @Test
    void duplicateTopLevelFilesKeyIsRejectedAsNotJson() {
        // Jackson's DEFAULT readTree() behavior for a duplicate object key is "last value wins, first is
        // silently discarded". With STRICT_DUPLICATE_DETECTION enabled, the duplicate key throws a
        // JsonParseException instead -- classified NOT_JSON, exactly like any other malformed response.
        String raw = """
                {"files":{"a.java":{"findings":[],"summary":"first"}},\
                "files":{"a.java":{"findings":[],"summary":"second"},"b.java":{"findings":[],"summary":"second-b"}},\
                "summary":"overall"}
                """;

        ValidationResult result = validate(raw, List.of("a.java", "b.java"));

        assertThat(result.isSuccess())
                .as("a duplicate top-level 'files' key must never validate successfully -- it must never "
                        + "silently discard the first object's content")
                .isFalse();
        assertThat(result.failure().kind()).isEqualTo(FailureKind.NOT_JSON);
    }

    @Test
    void duplicateNestedFileKeyWithinASingleFilesObjectIsRejectedAsNotJson() {
        // Same mechanism one level down: two entries for "A.java" inside the same "files" object. Before
        // the fix, the first entry's real finding was silently discarded in favor of the second (empty)
        // entry with no error and no signal. Now the duplicate key itself is rejected.
        String raw = """
                {"files":{"A.java":{"findings":[{"line":1,"severity":"major","comment":"first entry finding","suggestion":""}],"summary":"first"},\
                "A.java":{"findings":[],"summary":"second"}},"summary":"overall"}
                """;

        ValidationResult result = validate(raw, List.of("A.java"));

        assertThat(result.isSuccess())
                .as("a duplicate nested file key must never validate successfully -- content must never be "
                        + "silently discarded")
                .isFalse();
        assertThat(result.failure().kind()).isEqualTo(FailureKind.NOT_JSON);
    }

    @Test
    void nonDuplicateResponseStillParsesSuccessfullyWithStrictDuplicateDetectionEnabled() {
        // Regression guard: STRICT_DUPLICATE_DETECTION must not reject ordinary, non-duplicated responses.
        String raw = """
                {"files":{"a.java":{"findings":[],"summary":"ok"},"b.java":{"findings":[],"summary":"ok"}},"summary":"overall"}
                """;

        ValidationResult result = validate(raw, List.of("a.java", "b.java"));

        assertThat(result.isSuccess()).isTrue();
    }
}

package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.model.enums.Severity;
import com.review.gateway.service.StructuredResponseParser.FailureKind;
import com.review.gateway.service.StructuredResponseParser.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link StructuredResponseParser} (architecture §5.1, SRO-30-34; threat model SOR-11
 * CRITICAL, SOR-14 TRACKED).
 */
class StructuredResponseParserTest {

    private final GatewayProperties properties = new GatewayProperties();

    private StructuredResponseParser newParser() {
        CommentParser commentParser = new CommentParser(properties);
        CommentRenderer commentRenderer = new CommentRenderer(commentParser, new TextSanitizer(), properties);
        return new StructuredResponseParser(commentParser, commentRenderer, new TextSanitizer(), properties);
    }

    private ReviewSchemaBuilder.SchemaOptions defaultOptions() {
        return new ReviewSchemaBuilder.SchemaOptions(20, 1200, 2000, true);
    }

    private ValidationResult validate(String raw, List<String> expectedPaths) {
        return newParser().validate(raw, expectedPaths, false, null, null, defaultOptions());
    }

    // ---- SRO-67c: the empty-expected-set invariant ----

    @Test
    void emptyExpectedPathsThrowsRatherThanValidating() {
        StructuredResponseParser parser = newParser();

        assertThatThrownBy(() -> parser.validate("{}", List.of(), false, null, null, defaultOptions()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> parser.validate("{}", null, false, null, null, defaultOptions()))
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
    void perFileSummaryDisabledDoesNotRequireTheSummaryField() {
        String raw = """
                {"files":{"A.java":{"findings":[]}},"summary":"overall"}
                """;
        StructuredResponseParser parser = newParser();
        ReviewSchemaBuilder.SchemaOptions options = new ReviewSchemaBuilder.SchemaOptions(20, 1200, 2000, false);

        ValidationResult result = parser.validate(raw, List.of("A.java"), false, null, null, options);

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

        ValidationResult result = parser.validate("{}", List.of("A.java"), false, "length", null, defaultOptions());

        assertThat(result.failure().kind()).isEqualTo(FailureKind.TRUNCATED);
    }

    @Test
    void rawResponseTruncatedFlagIsClassifiedTruncated() {
        StructuredResponseParser parser = newParser();

        ValidationResult result = parser.validate("{}", List.of("A.java"), true, null, null, defaultOptions());

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

        ValidationResult result = parser.validate(raw, List.of("A.java"), false, null, null, tightOptions);

        assertThat(result.failure().kind()).isEqualTo(FailureKind.SCHEMA_MISMATCH);
    }

    @Test
    void commentExceedingMaxLengthIsSchemaMismatchEvenIfTheDecoderConstraintWasNotHonored() {
        // SRO-04: never trust the constraint -- re-checked independently on the Gateway side.
        String longComment = "x".repeat(50);
        String raw = "{\"files\":{\"A.java\":{\"findings\":[{\"line\":1,\"severity\":\"info\",\"comment\":\""
                + longComment + "\",\"suggestion\":\"\"}],\"summary\":\"s\"}},\"summary\":\"y\"}";
        StructuredResponseParser parser = newParser();
        ReviewSchemaBuilder.SchemaOptions tightOptions = new ReviewSchemaBuilder.SchemaOptions(20, 10, 2000, true);

        ValidationResult result = parser.validate(raw, List.of("A.java"), false, null, null, tightOptions);

        assertThat(result.failure().kind()).isEqualTo(FailureKind.SCHEMA_MISMATCH);
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

    @Test
    void classHasNoReferenceToBackendOrStructuredOutputMode() {
        for (var field : StructuredResponseParser.class.getDeclaredFields()) {
            String typeName = field.getType().getName();
            assertThat(typeName).doesNotContain("Backend");
            assertThat(typeName).doesNotContain("StructuredOutputMode");
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
}

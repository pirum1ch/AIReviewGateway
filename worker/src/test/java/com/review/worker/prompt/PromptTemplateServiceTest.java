package com.review.worker.prompt;

import com.review.worker.config.WorkerProperties;
import com.review.worker.error.AbandonJobException;
import com.review.worker.llama.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the WSR-01/WSR-02/WSR-03/WSR-07 requirements: {@code promptVersion} allowlisting (including
 * path-traversal-shaped injections) before any resource resolution, literal-only {@code {{DIFF}}}
 * substitution, diff-size enforcement, and template override precedence.
 *
 * <p>Uses the real {@code prompts/v1.yml} shipped on the test classpath (identical to the production
 * resource) plus a {@code prompts/with-overrides.yml} fixture added under {@code src/test/resources} to
 * exercise the override-precedence path.
 */
class PromptTemplateServiceTest {

    private PromptTemplateService service;

    @BeforeEach
    void setUp() {
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("default-model");
        properties.getLlama().setTemperature(0.2);
        properties.getLlama().setMaxTokens(2048);
        service = new PromptTemplateService(properties);
    }

    @Test
    void resolvesV1TemplateAndSubstitutesDiffLiterally() {
        ResolvedPrompt resolved = service.resolve("v1", "diff --git a/A.java b/A.java\n+System.out.println();");

        assertThat(resolved.messages()).isNotEmpty();
        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.role()).isEqualTo("user");
        assertThat(userMessage.content()).contains("diff --git a/A.java b/A.java");
        assertThat(userMessage.content()).doesNotContain("{{DIFF}}");
    }

    @Test
    void fallsBackToLlamaConfigWhenTemplateHasNoOverrides() {
        ResolvedPrompt resolved = service.resolve("v1", "some diff");

        assertThat(resolved.model()).isEqualTo("default-model");
        assertThat(resolved.temperature()).isEqualTo(0.2);
        assertThat(resolved.maxTokens()).isEqualTo(2048);
    }

    @Test
    void templateOverridesTakePrecedenceOverLlamaConfig() {
        ResolvedPrompt resolved = service.resolve("with-overrides", "some diff");

        assertThat(resolved.model()).isEqualTo("template-model");
        assertThat(resolved.temperature()).isEqualTo(0.7);
        assertThat(resolved.maxTokens()).isEqualTo(512);
    }

    @Test
    void diffContainingLiteralBracesIsNeverReinterpretedAsAnExpression() {
        String trickyDiff = "if (x) { ${7*7} #{7*7} <#if true>oops</#if> }";
        ResolvedPrompt resolved = service.resolve("v1", trickyDiff);

        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.content()).contains(trickyDiff);
        assertThat(userMessage.content()).doesNotContain("49");
    }

    @Test
    void unknownPromptVersionIsAbandoned() {
        assertThatThrownBy(() -> service.resolve("does-not-exist", "diff"))
                .isInstanceOf(AbandonJobException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../../etc/passwd",
            "..%2f..%2fetc%2fpasswd",
            "v1/../../../etc/passwd",
            "v1/",
            "..",
            "v1\nX-Injected: true",
            ""
    })
    void pathTraversalAndInjectionShapedPromptVersionsAreRejectedBeforeResourceResolution(String malicious) {
        assertThatThrownBy(() -> service.resolve(malicious, "diff"))
                .isInstanceOf(AbandonJobException.class);
    }

    @Test
    void nullPromptVersionIsRejected() {
        assertThatThrownBy(() -> service.resolve(null, "diff")).isInstanceOf(AbandonJobException.class);
    }

    // ---- V2 (diff chunking): chunkContext substitution ----

    @Test
    void resolvesV2TemplateAndSubstitutesChunkContextAndDiff() {
        ResolvedPrompt resolved = service.resolve("v2", "diff --git a/A.java b/A.java\n+x();", "part 2 of 5\nA.java");

        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.content()).contains("part 2 of 5");
        assertThat(userMessage.content()).contains("diff --git a/A.java b/A.java");
        assertThat(userMessage.content()).doesNotContain("{{CHUNK_CONTEXT}}");
        assertThat(userMessage.content()).doesNotContain("{{DIFF}}");
    }

    @Test
    void nullChunkContextSubstitutesToEmptyString() {
        ResolvedPrompt resolved = service.resolve("v2", "some diff", null);

        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.content()).doesNotContain("{{CHUNK_CONTEXT}}");
    }

    /**
     * CSR-08 (MUST): two sequential {@code String.replace} calls would be unsafe -- a file literally
     * named {@code {{DIFF}}} inside the chunk context, or a diff containing the literal text
     * {@code {{CHUNK_CONTEXT}}}, must resolve each placeholder EXACTLY once from the original template
     * text, with no cross-substitution in either direction.
     */
    @Test
    void placeholdersResolveExactlyOnceWithNoCrossSubstitutionEvenWhenContentContainsTheOtherPlaceholderLiterally() {
        String diffContainingChunkContextLiteral = "diff --git a/{{CHUNK_CONTEXT}}.java b/{{CHUNK_CONTEXT}}.java\n+ real diff content";
        String chunkContextContainingDiffLiteral = "part 1 of 2, file {{DIFF}}.java";

        ResolvedPrompt resolved = service.resolve("v2", diffContainingChunkContextLiteral, chunkContextContainingDiffLiteral);

        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        String content = userMessage.content();

        // The diff's literal "{{CHUNK_CONTEXT}}" text is DIFF content (substituted via {{DIFF}}, not a
        // template placeholder itself) and must survive unresolved/unexpanded. The chunkContext's literal
        // "{{DIFF}}" text has its brace characters stripped by the CSR-08 defense-in-depth pass (applied
        // to chunkContext before substitution) but must NOT have been replaced with the actual diff text.
        assertThat(content).contains("diff --git a/{{CHUNK_CONTEXT}}.java b/{{CHUNK_CONTEXT}}.java");
        assertThat(content).contains("part 1 of 2, file DIFF.java");
        // The template's own two real placeholders must each have been substituted exactly once.
        assertThat(content).contains("real diff content");
        // No cross-substitution: the count of "real diff content" must be exactly 1 (not duplicated by a
        // second, accidental substitution pass), and the same for the chunk-context text.
        assertThat(countOccurrences(content, "real diff content")).isEqualTo(1);
        assertThat(countOccurrences(content, "part 1 of 2, file")).isEqualTo(1);
    }

    /** CSR-08 defense in depth: literal {@code {{}}} sequences are stripped from chunkContext before substitution. */
    @Test
    void literalBraceSequencesAreStrippedFromChunkContextBeforeSubstitution() {
        ResolvedPrompt resolved = service.resolve("v2", "a diff", "part 1 of 2 {{INJECTED}}");

        ChatMessage userMessage = resolved.messages().get(resolved.messages().size() - 1);
        assertThat(userMessage.content()).doesNotContain("{{INJECTED}}");
        assertThat(userMessage.content()).contains("part 1 of 2 INJECTED");
    }

    /** CSR-12 (MUST): fail closed if chunkContext is supplied but the resolved template has no placeholder for it. */
    @Test
    void chunkContextSuppliedForATemplateWithNoPlaceholderIsAbandoned() {
        assertThatThrownBy(() -> service.resolve("v1", "some diff", "part 1 of 2"))
                .isInstanceOf(AbandonJobException.class);
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @Test
    void oversizedDiffIsAbandonedBeforeTemplateResolution() {
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getWorker().getLimits().setMaxDiffBytes(10);
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("default-model");
        PromptTemplateService smallLimitService = new PromptTemplateService(properties);

        assertThatThrownBy(() -> smallLimitService.resolve("v1", "a diff that is definitely too long"))
                .isInstanceOf(AbandonJobException.class);
    }

    /** Extends {@code worker.limits.max-diff-bytes} to cover {@code diff bytes + chunkContext bytes} combined. */
    @Test
    void combinedDiffAndChunkContextSizeIsBoundedTogether() {
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getWorker().getLimits().setMaxDiffBytes(20);
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("default-model");
        PromptTemplateService smallLimitService = new PromptTemplateService(properties);

        // Neither the diff nor the chunkContext alone exceeds 20 bytes, but their sum does.
        assertThatThrownBy(() -> smallLimitService.resolve("v2", "12345678901", "1234567890"))
                .isInstanceOf(AbandonJobException.class);
    }
}

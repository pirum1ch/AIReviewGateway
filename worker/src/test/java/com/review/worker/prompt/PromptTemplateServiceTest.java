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
        WorkerProperties properties = new WorkerProperties("127.0.0.1");
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

    @Test
    void oversizedDiffIsAbandonedBeforeTemplateResolution() {
        WorkerProperties properties = new WorkerProperties("127.0.0.1");
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
}

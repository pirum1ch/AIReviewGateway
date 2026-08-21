package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.PromptResolutionSaturatedException;
import com.review.gateway.exception.PromptSourceInvalidException;
import com.review.gateway.exception.PromptSourceMissingException;
import com.review.gateway.exception.PromptSourceUnavailableException;
import com.review.gateway.model.enums.PromptBundleMode;
import com.review.gateway.model.enums.PromptSectionKind;
import com.review.gateway.model.enums.PromptSectionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PromptManager} (PMR-09/10/11/17/19/21/26).
 */
class PromptManagerTest {

    private GatewayProperties properties;
    private GitLabClient gitLabClient;
    private PromptManager manager;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.getPrompt().setEnabled(true);
        properties.getPrompt().getCorporate().setProject("platform/ai-review-prompts");
        properties.getPrompt().getCorporate().setRef("main");
        properties.getPrompt().getCorporate().setBasePromptPath("prompts/base.md");
        properties.getPrompt().getCorporate().setReviewRulesPath("prompts/rules.md");
        properties.getPrompt().getLimits().setMaxConcurrentResolutions(4);
        properties.getPrompt().setTotalTimeout(Duration.ofSeconds(20));

        gitLabClient = Mockito.mock(GitLabClient.class);
        manager = new PromptManager(properties, gitLabClient, new PromptSourceResolver(properties),
                new PromptAssembler(properties, new DiffSizeValidator(properties)), new TextSanitizer());
    }

    // ---- kill-switch: zero GitLab calls (PMR-10 premise) ----

    @Test
    void disabledKillSwitchMakesZeroGitLabCallsAndReturnsNoneMode() {
        properties.getPrompt().setEnabled(false);

        PromptManager.PromptResolution result = manager.resolve(1042L);

        assertThat(result.mode()).isEqualTo(PromptBundleMode.NONE);
        assertThat(result.sections()).isEmpty();
        Mockito.verifyNoInteractions(gitLabClient);
    }

    // ---- corporate mandatory, always fails hard ----

    @Test
    void mandatoryCorporateFileMissingThrowsPromptSourceMissingException() {
        properties.getPrompt().getProject().setEnabled(false);
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("sha1");
        when(gitLabClient.fetchRawFile(eq("platform/ai-review-prompts"), eq("prompts/base.md"), eq("sha1"), anyInt()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.resolve(1042L)).isInstanceOf(PromptSourceMissingException.class);
    }

    @Test
    void corporateCommitResolutionFailureAlwaysFailsRegardlessOfOnErrorConfig() {
        properties.getPrompt().getErrorHandling().setOnError("SKIP_OPTIONAL");
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main"))
                .thenThrow(new PromptSourceUnavailableException("gitlab down"));

        assertThatThrownBy(() -> manager.resolve(1042L)).isInstanceOf(PromptSourceUnavailableException.class);
    }

    // ---- full happy path with project sections ----

    @Test
    void successfulResolutionProducesRepoModeWithFourSections() {
        properties.getPrompt().getProject().setEnabled(true);
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate base content"));
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/rules.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate rules content"));
        when(gitLabClient.resolveDefaultBranch("1042")).thenReturn("main");
        when(gitLabClient.resolveCommitSha("1042", "main")).thenReturn("projsha");
        when(gitLabClient.fetchRawFile("1042", ".ai-review/architecture.md", "projsha", 262144))
                .thenReturn(Optional.of("project architecture doc"));
        when(gitLabClient.fetchRawFile("1042", ".ai-review/code-rules.md", "projsha", 262144))
                .thenReturn(Optional.of("project code rules doc"));

        PromptManager.PromptResolution result = manager.resolve(1042L);

        assertThat(result.mode()).isEqualTo(PromptBundleMode.REPO);
        assertThat(result.sections()).hasSize(4);
        assertThat(result.degraded()).isFalse();
        assertThat(result.explicitPathsMissing()).isEmpty();
    }

    // ---- PMR-11: explicit override path 404 vs default path 404 ----

    @Test
    void explicitOverridePathNotFoundIsRecordedInExplicitPathsMissing() {
        GatewayProperties.Prompt.Project.Override override = new GatewayProperties.Prompt.Project.Override();
        override.setProject("1042");
        override.setRef("main");
        override.setArchitecturePath("typo-architecture.md");
        override.setCodeRulesPath("code-rules.md");
        properties.getPrompt().getProject().getOverrides().put("1042", override);
        manager = new PromptManager(properties, gitLabClient, new PromptSourceResolver(properties),
                new PromptAssembler(properties, new DiffSizeValidator(properties)), new TextSanitizer());

        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate base"));
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/rules.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate rules"));
        when(gitLabClient.resolveCommitSha("1042", "main")).thenReturn("projsha");
        when(gitLabClient.fetchRawFile("1042", "typo-architecture.md", "projsha", 262144))
                .thenReturn(Optional.empty());
        when(gitLabClient.fetchRawFile("1042", "code-rules.md", "projsha", 262144))
                .thenReturn(Optional.of("code rules content"));

        PromptManager.PromptResolution result = manager.resolve(1042L);

        assertThat(result.explicitPathsMissing()).containsExactly(PromptSectionKind.PROJECT_ARCHITECTURE);
        // resolveDefaultBranch is never called: this override pinned an explicit ref.
        verify(gitLabClient, never()).resolveDefaultBranch(any());
        var archSection = result.sections().stream()
                .filter(s -> s.kind() == PromptSectionKind.PROJECT_ARCHITECTURE).findFirst().orElseThrow();
        assertThat(archSection.status()).isEqualTo(PromptSectionStatus.ABSENT);
    }

    @Test
    void defaultPathNotFoundIsSilentNoSignal() {
        properties.getPrompt().getProject().setEnabled(true);
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate base"));
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/rules.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate rules"));
        when(gitLabClient.resolveDefaultBranch("1042")).thenReturn("main");
        when(gitLabClient.resolveCommitSha("1042", "main")).thenReturn("projsha");
        when(gitLabClient.fetchRawFile("1042", ".ai-review/architecture.md", "projsha", 262144))
                .thenReturn(Optional.empty());
        when(gitLabClient.fetchRawFile("1042", ".ai-review/code-rules.md", "projsha", 262144))
                .thenReturn(Optional.empty());

        PromptManager.PromptResolution result = manager.resolve(1042L);

        assertThat(result.explicitPathsMissing()).isEmpty();
        assertThat(result.sections().stream().filter(s -> s.status() == PromptSectionStatus.ABSENT)).hasSize(2);
    }

    // ---- SKIP_OPTIONAL vs FAIL for project source failures ----

    @Test
    void projectSourceFailureWithFailOnErrorPropagates() {
        properties.getPrompt().getErrorHandling().setOnError("FAIL");
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate base"));
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/rules.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate rules"));
        when(gitLabClient.resolveDefaultBranch("1042")).thenThrow(new PromptSourceUnavailableException("down"));

        assertThatThrownBy(() -> manager.resolve(1042L)).isInstanceOf(PromptSourceUnavailableException.class);
    }

    @Test
    void projectSourceFailureWithSkipOptionalDegradesInsteadOfFailing() {
        properties.getPrompt().getErrorHandling().setOnError("SKIP_OPTIONAL");
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate base"));
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/rules.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate rules"));
        when(gitLabClient.resolveDefaultBranch("1042")).thenThrow(new PromptSourceUnavailableException("down"));

        PromptManager.PromptResolution result = manager.resolve(1042L);

        assertThat(result.mode()).isEqualTo(PromptBundleMode.REPO);
        assertThat(result.degraded()).isTrue();
        assertThat(result.sections()).hasSize(2); // corporate only
    }

    /**
     * F-PM-01 regression (appsec SAST round, originally spotted by QA): {@code on-error=SKIP_OPTIONAL}
     * must absorb {@link com.review.gateway.exception.PromptSourceInvalidException} on an <em>optional</em>
     * PROJECT_* section exactly the way it absorbs {@link PromptSourceUnavailableException} — architecture
     * §3 step 4c says "other error =&gt; FAIL or SKIP_OPTIONAL per config", and
     * {@code PromptSourceInvalidException} (over max-file-bytes / invalid UTF-8 / NUL / empty) is squarely
     * an "other error". Before the fix the catch clause named only the Unavailable type, so any developer
     * able to land such a file on their project's default branch could 422 every {@code POST /reviews} for
     * that project permanently, with no operator escape hatch.
     */
    @Test
    void invalidOptionalProjectSectionIsSkippedUnderSkipOptional() {
        properties.getPrompt().getErrorHandling().setOnError("SKIP_OPTIONAL");
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate base"));
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/rules.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate rules"));
        when(gitLabClient.resolveDefaultBranch("1042")).thenReturn("main");
        when(gitLabClient.resolveCommitSha("1042", "main")).thenReturn("projsha");
        when(gitLabClient.fetchRawFile("1042", ".ai-review/architecture.md", "projsha", 262144))
                .thenThrow(new PromptSourceInvalidException("Prompt source file exceeds max-file-bytes"));

        PromptManager.PromptResolution result = manager.resolve(1042L);

        assertThat(result.mode()).isEqualTo(PromptBundleMode.REPO);
        assertThat(result.degraded()).isTrue();
        assertThat(result.sections()).hasSize(2); // corporate only -- the review still runs, under the rulebook
    }

    /** The other half of the same fix: with the default {@code on-error=FAIL} it must still fail hard. */
    @Test
    void invalidOptionalProjectSectionStillFailsHardUnderFail() {
        properties.getPrompt().getErrorHandling().setOnError("FAIL");
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate base"));
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/rules.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate rules"));
        when(gitLabClient.resolveDefaultBranch("1042")).thenReturn("main");
        when(gitLabClient.resolveCommitSha("1042", "main")).thenReturn("projsha");
        when(gitLabClient.fetchRawFile("1042", ".ai-review/architecture.md", "projsha", 262144))
                .thenThrow(new PromptSourceInvalidException("Prompt source file contains a NUL byte"));

        assertThatThrownBy(() -> manager.resolve(1042L)).isInstanceOf(PromptSourceInvalidException.class);
    }

    /**
     * F-PM-01 scope guard: a <em>mandatory corporate</em> section that is invalid must keep failing hard
     * even under {@code SKIP_OPTIONAL} — the widened catch must not have swallowed the corporate path
     * (corporate fetches happen above the try block; this pins that structural fact behaviourally).
     */
    @Test
    void invalidCorporateSectionAlwaysFailsEvenUnderSkipOptional() {
        properties.getPrompt().getErrorHandling().setOnError("SKIP_OPTIONAL");
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenThrow(new PromptSourceInvalidException("Prompt source file is not valid UTF-8"));

        assertThatThrownBy(() -> manager.resolve(1042L)).isInstanceOf(PromptSourceInvalidException.class);
    }

    // ---- F-PM-03: PromptTooLargeException from an oversized *optional* project section ----

    /**
     * F-PM-03 regression (appsec SAST round): before the fix, {@code PromptAssembler.assemble} threw
     * {@link com.review.gateway.exception.PromptTooLargeException} from outside the try/catch that
     * {@code on-error=SKIP_OPTIONAL} governs, so a genuinely oversized-but-valid optional
     * {@code PROJECT_ARCHITECTURE} doc 422'd every {@code POST /reviews} for that project with no
     * operator escape hatch, even under {@code SKIP_OPTIONAL}. Corporate-only content comfortably fits
     * the (deliberately tiny) budget below; only adding the project section pushes it over.
     */
    @Test
    void oversizedOptionalProjectSectionDegradesUnderSkipOptional() {
        properties.getPrompt().getErrorHandling().setOnError("SKIP_OPTIONAL");
        properties.getPrompt().getLimits().setMaxSystemPromptTokens(20); // corp-only ~8 tokens; +project overflows
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate base"));
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/rules.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate rules"));
        when(gitLabClient.resolveDefaultBranch("1042")).thenReturn("main");
        when(gitLabClient.resolveCommitSha("1042", "main")).thenReturn("projsha");
        when(gitLabClient.fetchRawFile("1042", ".ai-review/architecture.md", "projsha", 262144))
                .thenReturn(Optional.of("x".repeat(500))); // comfortably over max-file-bytes-independent token cap
        when(gitLabClient.fetchRawFile("1042", ".ai-review/code-rules.md", "projsha", 262144))
                .thenReturn(Optional.empty());

        PromptManager.PromptResolution result = manager.resolve(1042L);

        assertThat(result.mode()).isEqualTo(PromptBundleMode.REPO);
        assertThat(result.degraded()).isTrue();
        assertThat(result.sections()).hasSize(2); // corporate only -- the review still runs, under the rulebook
        assertThat(result.explicitPathsMissing()).isEmpty();
    }

    /** The other half of the same fix: with the default {@code on-error=FAIL} it must still 422 hard. */
    @Test
    void oversizedOptionalProjectSectionStillFailsHardUnderFail() {
        properties.getPrompt().getErrorHandling().setOnError("FAIL");
        properties.getPrompt().getLimits().setMaxSystemPromptTokens(20);
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate base"));
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/rules.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate rules"));
        when(gitLabClient.resolveDefaultBranch("1042")).thenReturn("main");
        when(gitLabClient.resolveCommitSha("1042", "main")).thenReturn("projsha");
        when(gitLabClient.fetchRawFile("1042", ".ai-review/architecture.md", "projsha", 262144))
                .thenReturn(Optional.of("x".repeat(500)));
        when(gitLabClient.fetchRawFile("1042", ".ai-review/code-rules.md", "projsha", 262144))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.resolve(1042L))
                .isInstanceOf(com.review.gateway.exception.PromptTooLargeException.class);
    }

    /**
     * F-PM-03 scope guard (PMR-21): when the mandatory CORPORATE_* content alone already exceeds the
     * budget, that must remain a hard failure regardless of {@code on-error} -- SKIP_OPTIONAL degrades
     * only the optional half, it must never be read as "silently truncate the corporate rulebook".
     */
    @Test
    void oversizedCorporateContentAloneAlwaysFailsEvenUnderSkipOptionalWithNoProjectSection() {
        properties.getPrompt().getErrorHandling().setOnError("SKIP_OPTIONAL");
        properties.getPrompt().getProject().setEnabled(false);
        properties.getPrompt().getLimits().setMaxSystemPromptTokens(1); // corp-only alone already overflows this
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate base"));
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/rules.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate rules"));

        assertThatThrownBy(() -> manager.resolve(1042L))
                .isInstanceOf(com.review.gateway.exception.PromptTooLargeException.class);
    }

    /**
     * Same scope guard, but with a project section present too: the corporate-only re-assembly (the
     * attribution step) must itself still overflow and propagate, rather than being masked by the
     * project-section-dropping degrade path.
     */
    @Test
    void oversizedCorporateContentAloneAlwaysFailsEvenWhenAProjectSectionIsAlsoPresent() {
        properties.getPrompt().getErrorHandling().setOnError("SKIP_OPTIONAL");
        properties.getPrompt().getLimits().setMaxSystemPromptTokens(1); // corp-only alone already overflows this
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main")).thenReturn("corpsha");
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/base.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate base"));
        when(gitLabClient.fetchRawFile("platform/ai-review-prompts", "prompts/rules.md", "corpsha", 262144))
                .thenReturn(Optional.of("corporate rules"));
        when(gitLabClient.resolveDefaultBranch("1042")).thenReturn("main");
        when(gitLabClient.resolveCommitSha("1042", "main")).thenReturn("projsha");
        when(gitLabClient.fetchRawFile("1042", ".ai-review/architecture.md", "projsha", 262144))
                .thenReturn(Optional.of("small"));
        when(gitLabClient.fetchRawFile("1042", ".ai-review/code-rules.md", "projsha", 262144))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.resolve(1042L))
                .isInstanceOf(com.review.gateway.exception.PromptTooLargeException.class);
    }

    // ---- PMR-19: bounded concurrency permit, immediate rejection on saturation ----

    @Test
    void saturatedConcurrencyPermitRejectsImmediatelyRatherThanQueueing() throws InterruptedException {
        properties.getPrompt().getLimits().setMaxConcurrentResolutions(1);
        properties.getPrompt().getProject().setEnabled(false);
        // The Semaphore's permit count is captured at PromptManager construction time -- must rebuild
        // after changing max-concurrent-resolutions above.
        manager = new PromptManager(properties, gitLabClient, new PromptSourceResolver(properties),
                new PromptAssembler(properties, new DiffSizeValidator(properties)), new TextSanitizer());
        CountDownLatch releaseLatch = new CountDownLatch(1);
        CountDownLatch enteredLatch = new CountDownLatch(1);
        when(gitLabClient.resolveCommitSha(any(), any())).thenAnswer(invocation -> {
            enteredLatch.countDown();
            releaseLatch.await(5, TimeUnit.SECONDS);
            return "sha1";
        });
        when(gitLabClient.fetchRawFile(any(), any(), any(), anyInt())).thenReturn(Optional.of("content"));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> manager.resolve(1042L));
            assertThat(enteredLatch.await(2, TimeUnit.SECONDS)).isTrue();

            // The first resolve() holds the only permit; a second concurrent call must be rejected
            // immediately (not block waiting for the first to finish).
            long start = System.nanoTime();
            assertThatThrownBy(() -> manager.resolve(1042L)).isInstanceOf(PromptResolutionSaturatedException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isLessThan(500);
        } finally {
            releaseLatch.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ---- PMR-26: coarse, undifferentiated error for all resolution failures ----

    @Test
    void allResolutionFailuresProduceTheSameExceptionTypeRegardlessOfCause() {
        // Cause 1: corporate commit SHA resolution fails.
        when(gitLabClient.resolveCommitSha("platform/ai-review-prompts", "main"))
                .thenThrow(new PromptSourceUnavailableException("cause A"));
        assertThatThrownBy(() -> manager.resolve(1042L)).isInstanceOf(PromptSourceUnavailableException.class);
    }
}

package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PromptSourceResolver} (pure function, no network I/O).
 */
class PromptSourceResolverTest {

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getPrompt().getCorporate().setProject("platform/ai-review-prompts");
        properties.getPrompt().getCorporate().setRef("main");
        properties.getPrompt().getCorporate().setBasePromptPath("prompts/base-system-prompt.md");
        properties.getPrompt().getCorporate().setReviewRulesPath("prompts/review-rules.md");
        return properties;
    }

    @Test
    void corporateReturnsConfiguredSource() {
        PromptSourceResolver resolver = new PromptSourceResolver(properties());

        PromptSourceResolver.CorporateSource corp = resolver.corporate();

        assertThat(corp.project()).isEqualTo("platform/ai-review-prompts");
        assertThat(corp.ref()).isEqualTo("main");
        assertThat(corp.basePromptPath()).isEqualTo("prompts/base-system-prompt.md");
        assertThat(corp.reviewRulesPath()).isEqualTo("prompts/review-rules.md");
    }

    @Test
    void projectWithNoOverrideUsesTheReviewedProjectItselfAndNullRef() {
        GatewayProperties properties = properties();
        PromptSourceResolver resolver = new PromptSourceResolver(properties);

        Optional<PromptSourceResolver.ProjectSource> result = resolver.project(1042L);

        assertThat(result).isPresent();
        PromptSourceResolver.ProjectSource source = result.get();
        assertThat(source.project()).isEqualTo("1042");
        // PMR-05: no override means the caller must resolve the project's own default branch via
        // GitLab -- never trust a client-supplied ref -- so explicitRef is null here.
        assertThat(source.explicitRef()).isNull();
        assertThat(source.architecturePath()).isEqualTo(".ai-review/architecture.md");
        assertThat(source.codeRulesPath()).isEqualTo(".ai-review/code-rules.md");
        assertThat(source.architecturePathExplicit()).isFalse();
        assertThat(source.codeRulesPathExplicit()).isFalse();
    }

    @Test
    void projectSectionsDisabledReturnsEmpty() {
        GatewayProperties properties = properties();
        properties.getPrompt().getProject().setEnabled(false);
        PromptSourceResolver resolver = new PromptSourceResolver(properties);

        assertThat(resolver.project(1042L)).isEmpty();
    }

    @Test
    void overrideWithExplicitRefAndPathsIsHonoured() {
        GatewayProperties properties = properties();
        GatewayProperties.Prompt.Project.Override override = new GatewayProperties.Prompt.Project.Override();
        override.setProject("org/team-a/ai-review-prompts");
        override.setRef("release-branch");
        override.setArchitecturePath("architecture.md");
        override.setCodeRulesPath("code-rules.md");
        properties.getPrompt().getProject().getOverrides().put("1042", override);
        PromptSourceResolver resolver = new PromptSourceResolver(properties);

        PromptSourceResolver.ProjectSource source = resolver.project(1042L).orElseThrow();

        assertThat(source.project()).isEqualTo("org/team-a/ai-review-prompts");
        assertThat(source.explicitRef()).isEqualTo("release-branch");
        assertThat(source.architecturePath()).isEqualTo("architecture.md");
        assertThat(source.codeRulesPath()).isEqualTo("code-rules.md");
        assertThat(source.architecturePathExplicit()).isTrue();
        assertThat(source.codeRulesPathExplicit()).isTrue();
    }

    @Test
    void overrideWithoutRefFallsBackToDefaultBranchResolution() {
        GatewayProperties properties = properties();
        GatewayProperties.Prompt.Project.Override override = new GatewayProperties.Prompt.Project.Override();
        override.setProject("org/team-a/ai-review-prompts");
        // ref left null -- override project's own default branch must still be resolved via GitLab.
        properties.getPrompt().getProject().getOverrides().put("1042", override);
        PromptSourceResolver resolver = new PromptSourceResolver(properties);

        PromptSourceResolver.ProjectSource source = resolver.project(1042L).orElseThrow();

        assertThat(source.explicitRef()).isNull();
    }

    @Test
    void overrideWithoutPathsFallsBackToProjectLevelDefaultPaths() {
        GatewayProperties properties = properties();
        GatewayProperties.Prompt.Project.Override override = new GatewayProperties.Prompt.Project.Override();
        override.setProject("org/team-a/ai-review-prompts");
        properties.getPrompt().getProject().getOverrides().put("1042", override);
        PromptSourceResolver resolver = new PromptSourceResolver(properties);

        PromptSourceResolver.ProjectSource source = resolver.project(1042L).orElseThrow();

        assertThat(source.architecturePath()).isEqualTo(".ai-review/architecture.md");
        assertThat(source.codeRulesPath()).isEqualTo(".ai-review/code-rules.md");
        assertThat(source.architecturePathExplicit()).isFalse();
        assertThat(source.codeRulesPathExplicit()).isFalse();
    }

    @Test
    void projectIdWithNoMatchingOverrideKeyUsesDefaultsEvenWhenOtherOverridesExist() {
        GatewayProperties properties = properties();
        GatewayProperties.Prompt.Project.Override override = new GatewayProperties.Prompt.Project.Override();
        override.setProject("org/team-a/ai-review-prompts");
        properties.getPrompt().getProject().getOverrides().put("999", override);
        PromptSourceResolver resolver = new PromptSourceResolver(properties);

        PromptSourceResolver.ProjectSource source = resolver.project(1042L).orElseThrow();

        assertThat(source.project()).isEqualTo("1042");
    }
}

package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Pure function {@code projectId -> sources} (architecture §2/§3): applies {@code overrides},
 * defaults, and the ref strategy from §0.1 (project sections are always read from a resolved default
 * branch, or an operator-pinned {@code ref} on an override — never the MR's own target/source branch).
 * No network I/O; every method here reads only already-loaded, deploy-time-validated
 * {@link GatewayProperties}.
 *
 * <p>ponytail: the architecture doc's §0.1/PMR-05 also allows an <em>optional</em> "verified-protected
 * target branch" mode (resolve the MR's own target branch only when {@code GET
 * /projects/{id}/protected_branches/{name}} confirms it is protected, falling back to the default
 * branch otherwise, with a {@code PROMPT_REF_FALLBACK} event). That mode is deliberately not
 * implemented here — this resolver only ever uses the project's own resolved default branch (or an
 * operator-pinned override ref), which fully satisfies PMR-05's MUST ("never from an unprotected,
 * MR-author-chosen ref") with less surface area. Add the protected-target-branch mode only if a real
 * per-release-branch review-rules requirement shows up.
 */
@Service
public class PromptSourceResolver {

    private final GatewayProperties properties;

    public PromptSourceResolver(GatewayProperties properties) {
        this.properties = properties;
    }

    /** The mandatory, org-wide corporate source (architecture §3 step 1) — same for every Review. */
    public CorporateSource corporate() {
        GatewayProperties.Prompt.Corporate corp = properties.getPrompt().getCorporate();
        return new CorporateSource(corp.getProject(), corp.getRef(), corp.getBasePromptPath(), corp.getReviewRulesPath());
    }

    /**
     * The optional, per-reviewed-project source, or {@link Optional#empty()} if
     * {@code gateway.prompt.project.enabled=false} (architecture §5).
     *
     * <p>PMR-05/§0.1: when no override is configured, the source project is the reviewed project
     * itself, and {@code explicitRef} is {@code null} — the caller must resolve the project's own
     * default branch via GitLab, never trust a client-supplied ref. When an override <em>is</em>
     * configured, {@code project} always comes from the override (validated non-blank at startup); its
     * {@code ref} may still be {@code null} (meaning "the override project's own default branch"), or a
     * pinned, operator-configured ref (trusted deploy-time config, not client input).
     */
    public Optional<ProjectSource> project(Long reviewedProjectId) {
        GatewayProperties.Prompt.Project projCfg = properties.getPrompt().getProject();
        if (!projCfg.isEnabled()) {
            return Optional.empty();
        }
        GatewayProperties.Prompt.Project.Override override =
                projCfg.getOverrides().get(String.valueOf(reviewedProjectId));
        if (override == null) {
            return Optional.of(new ProjectSource(String.valueOf(reviewedProjectId), null,
                    projCfg.getArchitecturePath(), false, projCfg.getCodeRulesPath(), false));
        }
        String architecturePath = override.getArchitecturePath() != null
                ? override.getArchitecturePath() : projCfg.getArchitecturePath();
        String codeRulesPath = override.getCodeRulesPath() != null
                ? override.getCodeRulesPath() : projCfg.getCodeRulesPath();
        return Optional.of(new ProjectSource(override.getProject(), override.getRef(),
                architecturePath, override.getArchitecturePath() != null,
                codeRulesPath, override.getCodeRulesPath() != null));
    }

    /** {@code basePromptPath}/{@code reviewRulesPath} are always mandatory — never "explicit vs. default" (PMR-11 doesn't apply to corporate). */
    public record CorporateSource(String project, String ref, String basePromptPath, String reviewRulesPath) {
    }

    /**
     * @param explicitRef              {@code null} means "resolve the project's own default branch via
     *                                  GitLab"; non-null is an operator-pinned ref from an override.
     * @param architecturePathExplicit {@code true} if this path came from an override entry (PMR-11: a
     *                                  404 on it is WARN + event, not silent).
     * @param codeRulesPathExplicit    same, for {@code codeRulesPath}.
     */
    public record ProjectSource(String project, String explicitRef, String architecturePath,
                                 boolean architecturePathExplicit, String codeRulesPath,
                                 boolean codeRulesPathExplicit) {
    }
}

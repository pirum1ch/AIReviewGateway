package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.PromptResolutionSaturatedException;
import com.review.gateway.exception.PromptSourceMissingException;
import com.review.gateway.exception.PromptSourceUnavailableException;
import com.review.gateway.model.enums.PromptBundleMode;
import com.review.gateway.model.enums.PromptSectionKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * Orchestrates one Review's prompt-source resolution end to end (architecture §2/§3): sources → fetch
 * → sanitize → assemble → cap. Not {@code @Transactional}, no DB access itself — a GitLab HTTP call
 * must never hold a Hikari connection or a row lock (architecture §3). Called once, synchronously, from
 * {@code ReviewService.createReview} — after the dedup lookup (no point calling GitLab for a request
 * that will be deduplicated), before {@code persistNewReview}.
 *
 * <p>PMR-19: the whole resolve runs under a bounded {@link Semaphore} permit
 * ({@code gateway.prompt.limits.max-concurrent-resolutions}) acquired non-blockingly — saturation is an
 * immediate {@link PromptResolutionSaturatedException} (503), never a queued thread — plus a wall-clock
 * deadline ({@code gateway.prompt.total-timeout}) checked between every outbound call, so a slow/hung
 * GitLab cannot silently consume far more than the configured budget across the up to 6 calls one
 * resolve can make.
 *
 * <p>ponytail: PMR-20 (SHOULD) — a small in-memory, content-addressed cache keyed on
 * {@code (project, path, commitSha)} (immutable by construction, so no staleness risk) is not
 * implemented; every resolve re-fetches the corporate sections from GitLab even though they are
 * identical across every Review until the corporate repo's next commit. Not needed at 20-30 MR/day (§11
 * of the architecture doc: add it when GitLab starts rate-limiting, or when {@code POST /reviews} p95
 * latency exceeds ~2-3s — a bounded {@code ConcurrentHashMap}, not Caffeine, per the project's stdlib-
 * only convention for this feature).
 *
 * <p>ponytail: PMR-27 (SHOULD) — no {@code gateway.prompt.allowed-project-ids} allowlist gate is
 * implemented; any project the shared CI token can name may have its default branch/prompt sections
 * read (PMT-08, an amplification of the pre-existing T-21/SR-16 residual, not a new one — see
 * {@code docs/prompt-manager-threat-model.md} §4's PMR-27 note). Add the allowlist once per-project CI
 * tokens are not yet available but cross-project reach needs to be bounded sooner.
 */
@Service
public class PromptManager {

    private static final Logger log = LoggerFactory.getLogger(PromptManager.class);
    private static final String ON_ERROR_SKIP_OPTIONAL = "SKIP_OPTIONAL";

    private final GatewayProperties properties;
    private final GitLabClient gitLabClient;
    private final PromptSourceResolver sourceResolver;
    private final PromptAssembler assembler;
    private final TextSanitizer textSanitizer;
    private final Semaphore permits;

    public PromptManager(GatewayProperties properties, GitLabClient gitLabClient,
                          PromptSourceResolver sourceResolver, PromptAssembler assembler,
                          TextSanitizer textSanitizer) {
        this.properties = properties;
        this.gitLabClient = gitLabClient;
        this.sourceResolver = sourceResolver;
        this.assembler = assembler;
        this.textSanitizer = textSanitizer;
        this.permits = new Semaphore(Math.max(1, properties.getPrompt().getLimits().getMaxConcurrentResolutions()));
    }

    /**
     * Result of {@link #resolve}: the sections to persist (empty for {@link PromptBundleMode#NONE}),
     * the aggregate token estimate, whether project-section resolution was degraded (skipped due to
     * {@code on-error=SKIP_OPTIONAL}), and the explicitly-configured-override paths that were looked up
     * and not found (PMR-11 — {@code ReviewService} emits one {@code PROMPT_SECTION_MISSING} event per
     * entry, once the Review row exists to attribute it to).
     */
    public record PromptResolution(PromptBundleMode mode, List<PromptAssembler.AssembledSection> sections,
                                    int estimatedTokens, boolean degraded, List<PromptSectionKind> explicitPathsMissing) {

        public static PromptResolution none() {
            return new PromptResolution(PromptBundleMode.NONE, List.of(), 0, false, List.of());
        }
    }

    public PromptResolution resolve(Long reviewedProjectId) {
        if (!properties.getPrompt().isEnabled()) {
            return PromptResolution.none();
        }
        if (!permits.tryAcquire()) {
            throw new PromptResolutionSaturatedException(
                    "gateway.prompt.limits.max-concurrent-resolutions saturated; try again shortly");
        }
        try {
            Instant deadline = Instant.now().plus(properties.getPrompt().getTotalTimeout());
            return doResolve(reviewedProjectId, deadline);
        } finally {
            permits.release();
        }
    }

    private PromptResolution doResolve(Long reviewedProjectId, Instant deadline) {
        PromptSourceResolver.CorporateSource corp = sourceResolver.corporate();

        checkDeadline(deadline);
        String corpSha = gitLabClient.resolveCommitSha(corp.project(), corp.ref());

        checkDeadline(deadline);
        String corpBaseRaw = gitLabClient.fetchRawFile(corp.project(), corp.basePromptPath(), corpSha, maxFileBytes())
                .orElseThrow(() -> new PromptSourceMissingException(
                        "Mandatory corporate prompt source is missing: base-prompt-path"));

        checkDeadline(deadline);
        String corpRulesRaw = gitLabClient.fetchRawFile(corp.project(), corp.reviewRulesPath(), corpSha, maxFileBytes())
                .orElseThrow(() -> new PromptSourceMissingException(
                        "Mandatory corporate prompt source is missing: review-rules-path"));

        PromptAssembler.SectionCandidate corpBaseCandidate = new PromptAssembler.SectionCandidate(
                PromptSectionKind.CORPORATE_BASE, true, textSanitizer.sanitizeSectionText(corpBaseRaw),
                corp.project(), corp.basePromptPath(), corp.ref(), corpSha);
        PromptAssembler.SectionCandidate corpRulesCandidate = new PromptAssembler.SectionCandidate(
                PromptSectionKind.CORPORATE_REVIEW_RULES, true, textSanitizer.sanitizeSectionText(corpRulesRaw),
                corp.project(), corp.reviewRulesPath(), corp.ref(), corpSha);

        PromptAssembler.SectionCandidate projectArchitecture = null;
        PromptAssembler.SectionCandidate projectCodeRules = null;
        boolean degraded = false;
        List<PromptSectionKind> explicitPathsMissing = new java.util.ArrayList<>();

        Optional<PromptSourceResolver.ProjectSource> projectSourceOpt = sourceResolver.project(reviewedProjectId);
        if (projectSourceOpt.isPresent()) {
            PromptSourceResolver.ProjectSource proj = projectSourceOpt.get();
            try {
                checkDeadline(deadline);
                String ref = proj.explicitRef() != null ? proj.explicitRef() : gitLabClient.resolveDefaultBranch(proj.project());

                checkDeadline(deadline);
                String projSha = gitLabClient.resolveCommitSha(proj.project(), ref);

                checkDeadline(deadline);
                projectArchitecture = fetchOptionalSection(PromptSectionKind.PROJECT_ARCHITECTURE, proj.project(),
                        proj.architecturePath(), ref, projSha, proj.architecturePathExplicit(), explicitPathsMissing);

                checkDeadline(deadline);
                projectCodeRules = fetchOptionalSection(PromptSectionKind.PROJECT_CODE_RULES, proj.project(),
                        proj.codeRulesPath(), ref, projSha, proj.codeRulesPathExplicit(), explicitPathsMissing);
            } catch (PromptSourceUnavailableException unavailable) {
                if (ON_ERROR_SKIP_OPTIONAL.equals(properties.getPrompt().getErrorHandling().getOnError())) {
                    log.warn("Project prompt source unavailable; skipping optional PROJECT_* sections "
                            + "(prompt_degraded=true): {}", unavailable.getClass().getSimpleName());
                    degraded = true;
                    projectArchitecture = null;
                    projectCodeRules = null;
                    explicitPathsMissing.clear();
                } else {
                    throw unavailable;
                }
            }
        }

        PromptAssembler.ResolvedSystemPrompt resolved = assembler.assemble(
                corpBaseCandidate, corpRulesCandidate, projectArchitecture, projectCodeRules, degraded);
        return new PromptResolution(PromptBundleMode.REPO, resolved.sections(), resolved.estimatedTokens(),
                resolved.degraded(), List.copyOf(explicitPathsMissing));
    }

    /**
     * PMR-11: a 404 on an explicitly-configured override path is recorded (via {@code explicitPathsMissing})
     * for the caller to WARN + emit a {@code PROMPT_SECTION_MISSING} event on; a 404 on the default path is
     * normal and silent. Either way an {@code ABSENT} candidate is returned (never {@code null}) — the
     * absence itself is always positively recorded in {@code review_prompt_sections}.
     */
    private PromptAssembler.SectionCandidate fetchOptionalSection(PromptSectionKind kind, String project, String path,
                                                                    String ref, String commitSha, boolean explicit,
                                                                    List<PromptSectionKind> explicitPathsMissing) {
        Optional<String> raw = gitLabClient.fetchRawFile(project, path, commitSha, maxFileBytes());
        if (raw.isPresent()) {
            return new PromptAssembler.SectionCandidate(kind, true, textSanitizer.sanitizeSectionText(raw.get()),
                    project, path, ref, commitSha);
        }
        if (explicit) {
            log.warn("Explicitly-configured prompt source path not found (kind={}, project={}, pathLength={})",
                    kind, project, path.length());
            explicitPathsMissing.add(kind);
        }
        return new PromptAssembler.SectionCandidate(kind, false, null, project, path, ref, commitSha);
    }

    private int maxFileBytes() {
        return properties.getPrompt().getLimits().getMaxFileBytes();
    }

    private void checkDeadline(Instant deadline) {
        if (Instant.now().isAfter(deadline)) {
            throw new PromptSourceUnavailableException(
                    "gateway.prompt.total-timeout exceeded while resolving prompt sources");
        }
    }
}

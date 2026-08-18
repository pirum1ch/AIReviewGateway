package com.review.gateway.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Typed {@code gateway.*} configuration surface. Extended in feature/03-api-security with the
 * GitLab/backend/security sub-trees and scheduler intervals (§9); the feature/02 fields (diff,
 * heartbeat, retry, job, publish) are unchanged and considered stable.
 *
 * <p>Registered directly via {@code @Component} (rather than {@code @EnableConfigurationProperties}
 * on a separate {@code @Configuration} class) so it is available for constructor injection into
 * services immediately.
 *
 * <p>{@link #validateOnStartup()} enforces SR-01: the three self-issued bearer tokens (CI/Worker/
 * Admin) must be present and at least 32 characters — a leaked/misconfigured short token fails the
 * Gateway's startup rather than silently authenticating with a guessable value. {@code
 * gateway.gitlab.token} is checked for presence only (no length floor): unlike the three tokens
 * above, it is issued by GitLab itself in a fixed format the operator does not control — a GitLab
 * project/group access token is exactly 26 characters ({@code glpat-} + 20), so applying the same
 * 32-character floor would reject every real GitLab token unconditionally. Also enforces SR-15 (the
 * GitLab base URL must be {@code https}). It runs via {@code @PostConstruct}, so it only
 * fires when this class is instantiated as a real Spring bean (production, {@code @SpringBootTest});
 * plain unit tests that do {@code new GatewayProperties()} never trigger it.
 */
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private static final Logger log = LoggerFactory.getLogger(GatewayProperties.class);

    private static final int MIN_SECRET_LENGTH = 32;
    /** WOR-14: an upper cap on {@code gateway.retry.requeue-delay} so a typo cannot strand a Review. */
    private static final Duration MAX_REQUEUE_DELAY = Duration.ofMinutes(10);
    /**
     * WOR-16: the Worker's own request timeout ({@code network.gateway-timeout-sec}, default 10s) is the
     * quantity a non-zero {@code requeue-delay} must be bounded against, not its (much shorter) poll
     * interval — a stale duplicate {@code /fail} report cannot arrive more than one Worker request
     * timeout late.
     */
    private static final Duration MIN_NONZERO_REQUEUE_DELAY = Duration.ofSeconds(15);

    private final Diff diff = new Diff();
    private final Heartbeat heartbeat = new Heartbeat();
    private final Retry retry = new Retry();
    private final Job job = new Job();
    private final Publish publish = new Publish();
    private final GitLab gitlab = new GitLab();
    private final Security security = new Security();
    private final Backend backend = new Backend();
    private final Scheduler scheduler = new Scheduler();
    private final Prompt prompt = new Prompt();

    public Diff getDiff() {
        return diff;
    }

    public Heartbeat getHeartbeat() {
        return heartbeat;
    }

    public Retry getRetry() {
        return retry;
    }

    public Job getJob() {
        return job;
    }

    public Publish getPublish() {
        return publish;
    }

    public GitLab getGitlab() {
        return gitlab;
    }

    public Security getSecurity() {
        return security;
    }

    public Backend getBackend() {
        return backend;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    /** PMR-14: project reference = numeric id, or up to 10 {@code /}-separated path segments — never a scheme/host. */
    private static final Pattern PROJECT_REF_PATTERN =
            Pattern.compile("^[0-9]+$|^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+){1,10}$");
    /** PMR-14: a Git ref name, no {@code ..} traversal shape. */
    private static final Pattern REF_PATTERN = Pattern.compile("^[A-Za-z0-9._/-]{1,255}$");
    /** PMR-13/PMR-14: a repo-relative file path, no leading {@code /}, no {@code ..} segment. */
    private static final Pattern SOURCE_PATH_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    private static final int MAX_SOURCE_PATH_LENGTH = 200;
    private static final int MAX_OVERRIDES = 500;

    @PostConstruct
    void validateOnStartup() {
        requireSecret("gateway.security.ci-token", security.getCiToken());
        requireSecret("gateway.security.worker-token", security.getWorkerToken());
        requireSecret("gateway.security.admin-token", security.getAdminToken());
        requireGitLabToken("gateway.gitlab.token", gitlab.getToken());

        if (gitlab.getBaseUrl() == null || !gitlab.getBaseUrl().startsWith("https://")) {
            throw new IllegalStateException(
                    "gateway.gitlab.base-url must use https:// (SR-15); got: " + describeUrlScheme(gitlab.getBaseUrl()));
        }

        if (diff.getMaxChunks() < 1) {
            throw new IllegalStateException("gateway.diff.max-chunks must be >= 1; got: " + diff.getMaxChunks());
        }
        if (diff.getChunkHeaderReserveTokens() < 0) {
            throw new IllegalStateException("gateway.diff.chunk-header-reserve-tokens must be >= 0; got: "
                    + diff.getChunkHeaderReserveTokens());
        }
        if (diff.getMaxChunkContextChars() < 1) {
            throw new IllegalStateException("gateway.diff.max-chunk-context-chars must be >= 1; got: "
                    + diff.getMaxChunkContextChars());
        }
        if (job.getMaxFailBodyBytes() < 1) {
            throw new IllegalStateException("gateway.job.max-fail-body-bytes must be >= 1; got: "
                    + job.getMaxFailBodyBytes());
        }

        validateRetryAndBackendHealthOnStartup();
        validatePromptOnStartup();
    }

    /**
     * Worker Observability & Claim Latency (WOR-01/WOR-14/WOR-16, blocking): the attempt budget must not
     * be exhaustible inside one backend-demotion grace window, {@code not_before} must never strand a
     * Review indefinitely, and the stale-duplicate-report window (SR-06 residual) must stay bounded by
     * the Worker's own request timeout, not its poll interval (the architecture doc's original — now
     * corrected — reasoning). Same {@code @PostConstruct} fail-fast pattern as the rest of this class.
     */
    private void validateRetryAndBackendHealthOnStartup() {
        Duration requeueDelay = retry.getRequeueDelay();
        Duration failureGrace = backend.getFailureGrace();
        if (requeueDelay == null || requeueDelay.isNegative()) {
            throw new IllegalStateException("gateway.retry.requeue-delay must be >= 0 — refusing to start");
        }
        if (failureGrace == null || failureGrace.isNegative()) {
            throw new IllegalStateException("gateway.backend.failure-grace must be >= 0 — refusing to start");
        }
        // WOR-14: an upper cap so a typo (e.g. a missing unit suffix) cannot strand a Review in QUEUED
        // forever behind an enormous not_before.
        if (requeueDelay.compareTo(MAX_REQUEUE_DELAY) > 0) {
            throw new IllegalStateException(
                    "gateway.retry.requeue-delay must be <= " + MAX_REQUEUE_DELAY + " — refusing to start");
        }

        boolean requeueDelayZero = requeueDelay.isZero();
        boolean failureGraceZero = failureGrace.isZero();
        if (requeueDelayZero && failureGraceZero) {
            // WOR-01/WOR-16: the paired, explicit "revert to pre-branch behavior" escape hatch -- neither
            // may be zeroed alone (checked below). This re-accepts both the WOT-01 fast-fail-storm risk
            // and the WOT-11 stale-duplicate-report residual; both are logged loudly at boot.
            log.warn("gateway.retry.requeue-delay=0 and gateway.backend.failure-grace=0: reverting to "
                    + "pre-branch behavior (immediate requeue, single-probe demotion) -- explicitly "
                    + "re-accepting the SR-06 stale-duplicate-report residual (WOR-16) and the fast-fail "
                    + "attempt-budget-exhaustion risk this branch otherwise closes (WOR-01)");
            return;
        }
        if (requeueDelayZero != failureGraceZero) {
            throw new IllegalStateException(
                    "gateway.retry.requeue-delay and gateway.backend.failure-grace must either both be 0 "
                            + "(explicit revert to pre-branch behavior) or both be non-zero — refusing to start");
        }

        // WOR-16: bounded by the Worker's request timeout (network.gateway-timeout-sec, default 10s), not
        // its poll interval -- a stale duplicate /fail report cannot be more than one Worker request
        // timeout late, so the delay must outlast that, not the much shorter poll cadence.
        if (requeueDelay.compareTo(MIN_NONZERO_REQUEUE_DELAY) < 0) {
            throw new IllegalStateException(
                    "gateway.retry.requeue-delay must be >= " + MIN_NONZERO_REQUEUE_DELAY
                            + " (bounded by the Worker's network.gateway-timeout-sec, WOR-16) or exactly 0 "
                            + "(paired with gateway.backend.failure-grace=0) — refusing to start");
        }

        // WOR-01: the attempt budget must not be exhaustible inside one backend-demotion grace window --
        // requeue-delay * (max-attempts - 1) is the minimum wall-clock time to burn every attempt via
        // worker-reported failures, and that must be at least the grace window a dead backend is allowed
        // to keep receiving claims for.
        int maxAttempts = retry.getMaxAttempts();
        if (maxAttempts < 1) {
            throw new IllegalStateException("gateway.retry.max-attempts must be >= 1; got: " + maxAttempts);
        }
        Duration attemptBudgetWindow = requeueDelay.multipliedBy(Math.max(0, maxAttempts - 1));
        if (attemptBudgetWindow.compareTo(failureGrace) < 0) {
            throw new IllegalStateException(
                    "gateway.retry.requeue-delay * (gateway.retry.max-attempts - 1) must be >= "
                            + "gateway.backend.failure-grace (WOR-01) — got requeue-delay=" + requeueDelay
                            + ", max-attempts=" + maxAttempts + " (budget window=" + attemptBudgetWindow
                            + "), failure-grace=" + failureGrace + " — refusing to start");
        }

        // A grace window shorter than one probe interval is meaningless (scheduler jitter could demote
        // on the very next tick regardless of the grace value).
        if (failureGrace.compareTo(scheduler.getBackendHealthInterval()) < 0) {
            throw new IllegalStateException(
                    "gateway.backend.failure-grace must be >= gateway.scheduler.backend-health-interval "
                            + "— refusing to start");
        }
    }

    /**
     * PMR-14/PMR-30: {@code gateway.prompt.*} is validated only when the kill-switch
     * ({@code gateway.prompt.enabled}) is on — an operator who has not opted into this feature (the
     * shipped default requires explicit corporate-project configuration) must not have the Gateway
     * refuse to start over an unrelated, unset new config tree; that would break "kill-switch off is
     * identical to today" (PMR-10's own premise). When enabled, every rule below is fail-fast, same
     * {@code @PostConstruct} pattern as SR-15.
     *
     * <p>ponytail: PMR-12 (SHOULD) — a startup dry-run that resolves every {@code overrides} entry once
     * and logs a consolidated WARN for unresolvable ones (so a typo surfaces at deploy, not "the
     * override silently never applied") is not implemented here; a misconfigured override still
     * degrades safely today (PMR-11: WARN + event + ABSENT row on every affected Review, not silence).
     * Add the dry-run once the {@code overrides} map is large enough that per-Review discovery of a
     * typo is too slow an operator feedback loop (rule of thumb: more than a handful of entries, or the
     * first time an override typo goes unnoticed for more than a few Reviews in practice).
     */
    private void validatePromptOnStartup() {
        if (!prompt.isEnabled()) {
            // PMR-10: the kill-switch is a legitimate operational control, but every Review created
            // while it's off must be traceable back to a deliberate, visible decision -- not just a
            // silent config default nobody noticed. The per-Review audit trail is PROMPT_DISABLED
            // events (ReviewService.persistNewReview) and GET /metrics (AdminController); this startup
            // WARN is the third, operator-facing signal, fired once per Gateway boot.
            log.warn("gateway.prompt.enabled=false: Reviews will be created without repo-sourced "
                    + "corporate/project prompt sections (legacy behavior) until this is re-enabled");
            return;
        }
        requireGitLabToken("gateway.gitlab.prompt-token", gitlab.getPromptToken());

        requireProjectRef("gateway.prompt.corporate.project", prompt.getCorporate().getProject());
        requireRef("gateway.prompt.corporate.ref", prompt.getCorporate().getRef());
        requireSourcePath("gateway.prompt.corporate.base-prompt-path", prompt.getCorporate().getBasePromptPath());
        requireSourcePath("gateway.prompt.corporate.review-rules-path", prompt.getCorporate().getReviewRulesPath());

        if (prompt.getProject().isEnabled()) {
            requireSourcePath("gateway.prompt.project.architecture-path", prompt.getProject().getArchitecturePath());
            requireSourcePath("gateway.prompt.project.code-rules-path", prompt.getProject().getCodeRulesPath());
        }

        Map<String, Prompt.Project.Override> overrides = prompt.getProject().getOverrides();
        if (overrides.size() > MAX_OVERRIDES) {
            throw new IllegalStateException(
                    "gateway.prompt.project.overrides must have at most " + MAX_OVERRIDES + " entries; got: "
                            + overrides.size());
        }
        for (Map.Entry<String, Prompt.Project.Override> entry : overrides.entrySet()) {
            String prefix = "gateway.prompt.project.overrides[" + entry.getKey() + "]";
            Prompt.Project.Override override = entry.getValue();
            requireProjectRef(prefix + ".project", override.getProject());
            if (override.getRef() != null) {
                requireRef(prefix + ".ref", override.getRef());
            }
            if (override.getArchitecturePath() != null) {
                requireSourcePath(prefix + ".architecture-path", override.getArchitecturePath());
            }
            if (override.getCodeRulesPath() != null) {
                requireSourcePath(prefix + ".code-rules-path", override.getCodeRulesPath());
            }
        }

        String onError = prompt.getErrorHandling().getOnError();
        if (!"FAIL".equals(onError) && !"SKIP_OPTIONAL".equals(onError)) {
            throw new IllegalStateException(
                    "gateway.prompt.error-handling.on-error must be FAIL or SKIP_OPTIONAL; got: " + onError);
        }
        String messageFormat = prompt.getMessageFormat();
        if (!"MULTI".equals(messageFormat) && !"SINGLE".equals(messageFormat)) {
            throw new IllegalStateException(
                    "gateway.prompt.message-format must be MULTI or SINGLE; got: " + messageFormat);
        }

        Prompt.Limits limits = prompt.getLimits();
        if (limits.getMaxFileBytes() < 1) {
            throw new IllegalStateException("gateway.prompt.limits.max-file-bytes must be >= 1");
        }
        if (limits.getMaxSystemPromptTokens() < 1) {
            throw new IllegalStateException("gateway.prompt.limits.max-system-prompt-tokens must be >= 1");
        }
        if (limits.getMaxSections() < 1) {
            throw new IllegalStateException("gateway.prompt.limits.max-sections must be >= 1");
        }
        if (limits.getMaxConcurrentResolutions() < 1) {
            throw new IllegalStateException("gateway.prompt.limits.max-concurrent-resolutions must be >= 1");
        }
        // PMR-21: the budget-consistency check -- there must be room left for a diff at all once the
        // system prompt has taken its maximum allowed size.
        int derivedFloor = diff.getContextWindow() - diff.getPromptReserve() - limits.getMaxSystemPromptTokens()
                - diff.getAnswerReserve();
        if (derivedFloor < limits.getMinDiffBudgetTokens()) {
            throw new IllegalStateException(
                    "gateway.prompt.limits budget is inconsistent: context-window - prompt-reserve - "
                            + "max-system-prompt-tokens - answer-reserve (" + derivedFloor
                            + ") must be >= min-diff-budget-tokens (" + limits.getMinDiffBudgetTokens() + ")");
        }

        if (prompt.getTotalTimeout().compareTo(prompt.getReadTimeout().multipliedBy(2)) < 0) {
            throw new IllegalStateException(
                    "gateway.prompt.total-timeout must be >= 2x gateway.prompt.read-timeout");
        }
    }

    private void requireProjectRef(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be set — refusing to start");
        }
        // F-PM-04: the '..' check is explicit rather than folded into PROJECT_REF_PATTERN, because a bare
        // ".." *does* match that pattern's [A-Za-z0-9._-]+ segment class -- PMR-14 requires '..' to be
        // rejected here, exactly as requireRef/requireSourcePath already do.
        if (!PROJECT_REF_PATTERN.matcher(value).matches() || value.contains("..")) {
            throw new IllegalStateException(
                    propertyName + " must be a numeric project id or a 'group/project'-style path with no '..' "
                            + "segment, never a URL (PMR-14) — refusing to start");
        }
    }

    private void requireRef(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be set — refusing to start");
        }
        if (!REF_PATTERN.matcher(value).matches() || value.contains("..")) {
            throw new IllegalStateException(propertyName + " must be a valid ref name with no '..' — refusing to start");
        }
    }

    private void requireSourcePath(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be set — refusing to start");
        }
        if (value.length() > MAX_SOURCE_PATH_LENGTH) {
            throw new IllegalStateException(
                    propertyName + " must be at most " + MAX_SOURCE_PATH_LENGTH + " characters — refusing to start");
        }
        if (value.startsWith("/") || value.contains("..") || !SOURCE_PATH_PATTERN.matcher(value).matches()) {
            throw new IllegalStateException(
                    propertyName + " must be a repo-relative path with no leading '/' and no '..' segment "
                            + "— refusing to start");
        }
    }

    private void requireSecret(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be set (SR-01) — refusing to start");
        }
        if (value.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    propertyName + " must be at least " + MIN_SECRET_LENGTH + " characters (SR-01) — refusing to start");
        }
    }

    /**
     * gateway.gitlab.token is GitLab's own, not something an operator picks the entropy of the way
     * they do the three bearer tokens above — a real project/group access token is a fixed 26
     * characters ({@code glpat-} + 20), so only presence is checked here (SR-01's "missing/blank"
     * half), not length.
     */
    private void requireGitLabToken(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must be set (SR-01) — refusing to start");
        }
    }

    /** Never echoes the actual configured value, only whether a scheme is present, for the exception message. */
    private String describeUrlScheme(String url) {
        if (url == null) {
            return "(not set)";
        }
        int schemeEnd = url.indexOf("://");
        return schemeEnd > 0 ? url.substring(0, schemeEnd) + "://..." : "(no scheme)";
    }

    /** Diff-size token-budget heuristic (§9 {@code gateway.diff.*}). */
    public static class Diff {
        /** Total LLM context window, in tokens. */
        private int contextWindow = 16384;
        /** Tokens reserved for the fixed prompt scaffolding. */
        private int promptReserve = 2000;
        /** Tokens reserved for the model's answer. */
        private int answerReserve = 4000;
        /** Explicit derived cap; the enforced budget is {@code min(this, contextWindow - reserves)}. */
        private int maxDiffTokens = 10000;
        /** Heuristic characters-per-token ratio used to estimate diff size without a real tokenizer. */
        private int charsPerToken = 4;
        /**
         * CSR-05: maximum number of chunks {@code DiffChunker} may split one diff into; more than this
         * is rejected with {@code DIFF_TOO_LARGE} rather than dispatched. Default deliberately lowered
         * to <b>5</b> (not the architect's original draft of 10) — the threat model flagged 10 as too
         * high a pool-starvation risk for launch, given the compute-cost math below.
         *
         * <p>Compute-cost bound this caps: in the worst case (every chunk retried to exhaustion) one
         * Review can occupy {@code max-chunks * gateway.job.max-duration * gateway.retry.max-attempts}
         * of aggregate Worker time before finally failing — with the stock defaults (45m max-duration,
         * 3 max-attempts) that is {@code 5 * 45m * 3 = 675} Worker-minutes for a single MR at the
         * default cap, versus {@code 10 * 45m * 3 = 1350} at the architect's original draft value. At
         * this project's scale (1-10 backends total), the lower default meaningfully bounds how much of
         * the shared pool one pathological MR can monopolize.
         */
        private int maxChunks = 5;
        /**
         * Tokens reserved, per chunk, for the {@code ChunkContextRenderer} cross-chunk header text that
         * gets injected into the prompt whenever a Review has more than one chunk (§3). Subtracted from
         * the per-chunk budget during bin-packing so the header always has room without pushing a chunk
         * over its real token budget; irrelevant (not subtracted) for the single-chunk case, since no
         * header is rendered then.
         */
        private int chunkHeaderReserveTokens = 256;
        /**
         * CSR-09/CSR-10: hard cap (characters) on the rendered cross-chunk context header text (file
         * paths from this chunk + the sanitized list of other files changed elsewhere in the MR).
         * Excess paths collapse to "... and N more" rather than growing the header unboundedly.
         */
        private int maxChunkContextChars = 1000;
        /**
         * SR-11 hard edge cap (bytes) for the whole {@code POST /reviews} request body, enforced by
         * {@code RequestBodySizeLimitFilter} before Spring MVC/Jackson reads it.
         *
         * <p>CSR-02: derived from {@code max-chunks * max-diff-tokens * chars-per-token * safety-factor
         * + fixed-overhead}, with the stock defaults ({@code max-chunks=5}, {@code max-diff-tokens=
         * 10000}, {@code chars-per-token=4}) giving {@code 5 * 10000 * 4 * 1.5 + 20000 = 320000}. The
         * {@code 1.5x} safety factor covers JSON-escaping overhead (quotes/backslashes/newlines in a
         * diff can each expand to a 2-6 char escape sequence); the {@code 20000}-byte fixed overhead
         * covers the request's other fields plus general slack. <b>If any of {@code max-chunks}/{@code
         * max-diff-tokens}/{@code chars-per-token} is changed in a deployment's config, this value must
         * be recomputed from the same formula and changed to match</b> — it is not automatically
         * derived at startup (unlike {@code budgetTokens()}) because the edge-level byte filter must
         * run before any config-driven request parsing, as cheaply as possible.
         */
        private long maxRequestBodyBytes = 320_000;

        public int getContextWindow() {
            return contextWindow;
        }

        public void setContextWindow(int contextWindow) {
            this.contextWindow = contextWindow;
        }

        public int getPromptReserve() {
            return promptReserve;
        }

        public void setPromptReserve(int promptReserve) {
            this.promptReserve = promptReserve;
        }

        public int getAnswerReserve() {
            return answerReserve;
        }

        public void setAnswerReserve(int answerReserve) {
            this.answerReserve = answerReserve;
        }

        public int getMaxDiffTokens() {
            return maxDiffTokens;
        }

        public void setMaxDiffTokens(int maxDiffTokens) {
            this.maxDiffTokens = maxDiffTokens;
        }

        public int getCharsPerToken() {
            return charsPerToken;
        }

        public void setCharsPerToken(int charsPerToken) {
            this.charsPerToken = charsPerToken;
        }

        public long getMaxRequestBodyBytes() {
            return maxRequestBodyBytes;
        }

        public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
            this.maxRequestBodyBytes = maxRequestBodyBytes;
        }

        public int getMaxChunks() {
            return maxChunks;
        }

        public void setMaxChunks(int maxChunks) {
            this.maxChunks = maxChunks;
        }

        public int getChunkHeaderReserveTokens() {
            return chunkHeaderReserveTokens;
        }

        public void setChunkHeaderReserveTokens(int chunkHeaderReserveTokens) {
            this.chunkHeaderReserveTokens = chunkHeaderReserveTokens;
        }

        public int getMaxChunkContextChars() {
            return maxChunkContextChars;
        }

        public void setMaxChunkContextChars(int maxChunkContextChars) {
            this.maxChunkContextChars = maxChunkContextChars;
        }
    }

    /** Worker heartbeat liveness window (§9 {@code gateway.heartbeat.*}). */
    public static class Heartbeat {
        /** A RUNNING job is stale if {@code now - heartbeat_at} exceeds this. */
        private Duration timeout = Duration.ofSeconds(180);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /** Retry limits (§9 {@code gateway.retry.*}). */
    public static class Retry {
        private int maxAttempts = 3;
        /**
         * Worker Observability & Claim Latency (WOC-40/WOR-01): delay before a requeued job becomes
         * claimable again, written to {@code review_jobs.not_before}. <b>Default 90s, not the
         * architecture doc's original 30s</b> — the threat model (WOR-01) requires
         * {@code requeue-delay * (max-attempts - 1) >= gateway.backend.failure-grace} (default 180s) so
         * the attempt budget can never be exhausted inside one backend-demotion grace window; with
         * {@code max-attempts=3} that arithmetic requires at least 90s. {@code 0} disables the mechanism
         * entirely (the documented escape hatch, paired with {@code failure-grace: 0} — see
         * {@link GatewayProperties#validateRetryAndBackendHealthOnStartup()}).
         */
        private Duration requeueDelay = Duration.ofSeconds(90);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getRequeueDelay() {
            return requeueDelay;
        }

        public void setRequeueDelay(Duration requeueDelay) {
            this.requeueDelay = requeueDelay;
        }
    }

    /** Hard job-duration backstop (§9 {@code gateway.job.*}). */
    public static class Job {
        private Duration maxDuration = Duration.ofMinutes(45);
        /**
         * WOC-33/WOR-08: SR-11 hard edge cap (bytes) for the whole {@code POST /jobs/{id}/fail} request
         * body, enforced by {@code RequestBodySizeLimitFilter}. Sized generously above the worst-case
         * body (~2 KB of UTF-8 + JSON escaping for a 32-char {@code reason} and a 500-char {@code detail}).
         */
        private long maxFailBodyBytes = 4096;

        public Duration getMaxDuration() {
            return maxDuration;
        }

        public void setMaxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
        }

        public long getMaxFailBodyBytes() {
            return maxFailBodyBytes;
        }

        public void setMaxFailBodyBytes(long maxFailBodyBytes) {
            this.maxFailBodyBytes = maxFailBodyBytes;
        }
    }

    /** Parse/publish caps (SR-08/SR-09/SR-21). */
    public static class Publish {
        /** Max number of parsed comments kept per Review; excess is dropped. */
        private int maxCommentCount = 50;
        /** Max characters kept per parsed comment; excess is truncated. */
        private int maxCommentLength = 4000;
        /** Max characters accepted for a raw LLM response at {@code /jobs/{id}/result} (SR-21). */
        private int maxRawResponseLength = 200_000;
        /**
         * SR-11 hard edge cap (bytes) for the whole {@code POST /jobs/{id}/result} request body,
         * enforced by {@code RequestBodySizeLimitFilter}. Sized above {@code maxRawResponseLength} to
         * allow for JSON-escaping overhead plus the request's other (small) fields.
         */
        private long maxRequestBodyBytes = 500_000;

        public int getMaxCommentCount() {
            return maxCommentCount;
        }

        public void setMaxCommentCount(int maxCommentCount) {
            this.maxCommentCount = maxCommentCount;
        }

        public int getMaxCommentLength() {
            return maxCommentLength;
        }

        public void setMaxCommentLength(int maxCommentLength) {
            this.maxCommentLength = maxCommentLength;
        }

        public int getMaxRawResponseLength() {
            return maxRawResponseLength;
        }

        public void setMaxRawResponseLength(int maxRawResponseLength) {
            this.maxRawResponseLength = maxRawResponseLength;
        }

        public long getMaxRequestBodyBytes() {
            return maxRequestBodyBytes;
        }

        public void setMaxRequestBodyBytes(long maxRequestBodyBytes) {
            this.maxRequestBodyBytes = maxRequestBodyBytes;
        }
    }

    /** GitLab discussions API client config (§9 {@code gateway.gitlab.*}). */
    public static class GitLab {
        private String baseUrl = "https://gitlab.example.com/api/v4";
        /** Masked by {@link #toString()} — never logged/echoed in plain text (SR-12). Write-scoped (discussions only). */
        private String token;
        /**
         * PMR-15/PMT-09: a separate, read-only credential ({@code read_api}/{@code read_repository})
         * used exclusively by {@code gitLabPromptRestClient} for all Prompt Manager fetches — never the
         * write-scoped {@link #token} above, and vice versa, so a leak of either credential is bounded
         * to its own blast radius (posting comments vs. reading repositories org-wide). Masked by
         * {@link #toString()}, same as {@link #token}.
         */
        private String promptToken;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(30);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getPromptToken() {
            return promptToken;
        }

        public void setPromptToken(String promptToken) {
            this.promptToken = promptToken;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        @Override
        public String toString() {
            return "GitLab{baseUrl='" + baseUrl + "', token=" + (token == null ? "null" : "***MASKED***")
                    + ", promptToken=" + (promptToken == null ? "null" : "***MASKED***") + "}";
        }
    }

    /** Bearer-token role config (§9 {@code gateway.security.*}, §7). */
    public static class Security {
        /** Masked by {@link #toString()} — never logged/echoed in plain text (SR-12). */
        private String ciToken;
        private String workerToken;
        private String adminToken;

        public String getCiToken() {
            return ciToken;
        }

        public void setCiToken(String ciToken) {
            this.ciToken = ciToken;
        }

        public String getWorkerToken() {
            return workerToken;
        }

        public void setWorkerToken(String workerToken) {
            this.workerToken = workerToken;
        }

        public String getAdminToken() {
            return adminToken;
        }

        public void setAdminToken(String adminToken) {
            this.adminToken = adminToken;
        }

        @Override
        public String toString() {
            return "Security{ciToken=***MASKED***, workerToken=***MASKED***, adminToken=***MASKED***}";
        }
    }

    /** llama-server health-probe client config (§9/§11, SR-10). */
    public static class Backend {
        private Duration connectTimeout = Duration.ofSeconds(3);
        /**
         * WOC-16: raised {@code 5s -> 10s}. Safe only because {@code BackendHealthChecker} (WOC-14) now
         * performs probe HTTP I/O outside any transaction, so a slow probe no longer pins a Hikari
         * connection.
         */
        private Duration readTimeout = Duration.ofSeconds(10);
        /**
         * Regex the probed backend's host must match, checked in addition to the always-enforced
         * loopback/link-local/metadata-range block (SR-10). Permissive by default ({@code ".*"} = any
         * host) since deployments vary (private LAN hostnames/IPs for Mac minis); operators should
         * tighten this in production config to their actual backend network.
         */
        private String allowedHostPattern = ".*";
        /**
         * WOC-11: continuous failed-probe streak required before {@code ACTIVE -> SUSPECT} (fail-slow).
         * {@code SUSPECT -> ACTIVE} stays single-success (recover-fast, unchanged). Must be
         * {@code >= gateway.scheduler.backend-health-interval} (validated at startup) and, per WOR-01,
         * {@code <= gateway.retry.requeue-delay * (gateway.retry.max-attempts - 1)}.
         */
        private Duration failureGrace = Duration.ofSeconds(180);
        /**
         * WOC-13: a failed probe does not demote a backend that is at capacity with at least one RUNNING
         * job whose heartbeat is still fresh — dispatch-neutral by construction (an at-capacity backend is
         * already unclaimable), further tightened over time by {@code BackendDispatcher} consulting
         * {@code probe_failed_since} directly (WOR-10).
         */
        private boolean deferDemotionWhileBusy = true;
        /**
         * WOR-13 (SHOULD): upper bound on how long the at-capacity deferral above may postpone a
         * demotion; past this, demotion proceeds regardless of capacity/heartbeat freshness. Defaults to
         * {@code gateway.job.max-duration} so a wedged-but-busy backend cannot defer forever.
         */
        private Duration deferDemotionMax = Duration.ofMinutes(45);

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public String getAllowedHostPattern() {
            return allowedHostPattern;
        }

        public void setAllowedHostPattern(String allowedHostPattern) {
            this.allowedHostPattern = allowedHostPattern;
        }

        public Duration getFailureGrace() {
            return failureGrace;
        }

        public void setFailureGrace(Duration failureGrace) {
            this.failureGrace = failureGrace;
        }

        public boolean isDeferDemotionWhileBusy() {
            return deferDemotionWhileBusy;
        }

        public void setDeferDemotionWhileBusy(boolean deferDemotionWhileBusy) {
            this.deferDemotionWhileBusy = deferDemotionWhileBusy;
        }

        public Duration getDeferDemotionMax() {
            return deferDemotionMax;
        }

        public void setDeferDemotionMax(Duration deferDemotionMax) {
            this.deferDemotionMax = deferDemotionMax;
        }
    }

    /** {@code @Scheduled} job intervals (§9 {@code gateway.scheduler.*}, §8). */
    public static class Scheduler {
        private Duration heartbeatCheckInterval = Duration.ofSeconds(30);
        private Duration backendHealthInterval = Duration.ofSeconds(60);
        private Duration publishRetryInterval = Duration.ofSeconds(60);

        public Duration getHeartbeatCheckInterval() {
            return heartbeatCheckInterval;
        }

        public void setHeartbeatCheckInterval(Duration heartbeatCheckInterval) {
            this.heartbeatCheckInterval = heartbeatCheckInterval;
        }

        public Duration getBackendHealthInterval() {
            return backendHealthInterval;
        }

        public void setBackendHealthInterval(Duration backendHealthInterval) {
            this.backendHealthInterval = backendHealthInterval;
        }

        public Duration getPublishRetryInterval() {
            return publishRetryInterval;
        }

        public void setPublishRetryInterval(Duration publishRetryInterval) {
            this.publishRetryInterval = publishRetryInterval;
        }
    }

    /**
     * Prompt Manager config (architecture §5, {@code gateway.prompt.*}).
     *
     * <p><b>Trust decision (PMT-24/PMR-30):</b> this whole subtree, including {@link Project#overrides},
     * is bound exclusively via {@code @ConfigurationProperties} from deploy-time YAML/env — it is
     * deploy-gated, trusted configuration, never runtime-mutable (no admin endpoint, no DB table, no
     * hot-reloaded file, no {@code Yaml.load()} of an operator-supplied file). If this ever becomes
     * runtime-mutable, {@code project}/{@code ref}/{@code paths} become attacker-influenced inputs to an
     * authenticated outbound fetch — a full SSRF + cross-project-read review is required first
     * (see {@code docs/prompt-manager-threat-model.md} PMT-24).
     */
    public static class Prompt {
        /**
         * Kill-switch: {@code false} = today's Worker-JAR-only behavior, zero GitLab calls (PMR-10).
         *
         * <p><b>F-PM-02:</b> defaults to {@code false} here, matching {@code application.yml}'s
         * {@code ${PROMPT_MANAGER_ENABLED:false}} — deliberately, so the two can never disagree the way
         * they did when this finding was raised (the YAML tree didn't exist at all, so this Java-level
         * default of {@code true} was silently the one in effect on every stock deployment, requiring a
         * corporate project and a GitLab token nothing had provisioned yet). An operator opts in
         * explicitly once {@code gateway.gitlab.prompt-token}/{@code gateway.prompt.corporate.project}
         * are actually configured; until then, a stock or upgraded Gateway boots exactly as it did before
         * this feature existed.
         */
        private boolean enabled = false;
        private final Corporate corporate = new Corporate();
        private final Project project = new Project();
        private final ErrorHandling errorHandling = new ErrorHandling();
        /** {@code MULTI} | {@code SINGLE} — default MULTI (PMR-22); {@code backends.prompt_message_format} overrides per-backend. */
        private String messageFormat = "MULTI";
        /** SINGLE mode only. */
        private String sectionSeparator = "\n\n---\n\n";
        private final Limits limits = new Limits();
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(8);
        /**
         * Wall-clock deadline for the whole resolve block (PMR-19), checked via {@code
         * PromptManager.checkDeadline} before each of the up to 6 outbound GitLab calls one resolve can
         * make. <b>F-PM-09:</b> this is a between-calls check, not an in-flight interrupt — a call that
         * begins just under the deadline can still run to its own {@link #readTimeout}, so the real
         * worst-case wall-clock bound for one resolve is {@code totalTimeout + readTimeout}, not {@code
         * totalTimeout} alone. The startup rule {@code totalTimeout >= 2 * readTimeout} already assumes
         * an operator reasons about the two together; this javadoc makes that assumption explicit rather
         * than only implicit in the validation arithmetic.
         */
        private Duration totalTimeout = Duration.ofSeconds(20);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Corporate getCorporate() {
            return corporate;
        }

        public Project getProject() {
            return project;
        }

        public ErrorHandling getErrorHandling() {
            return errorHandling;
        }

        public String getMessageFormat() {
            return messageFormat;
        }

        public void setMessageFormat(String messageFormat) {
            this.messageFormat = messageFormat;
        }

        public String getSectionSeparator() {
            return sectionSeparator;
        }

        public void setSectionSeparator(String sectionSeparator) {
            this.sectionSeparator = sectionSeparator;
        }

        public Limits getLimits() {
            return limits;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Duration getTotalTimeout() {
            return totalTimeout;
        }

        public void setTotalTimeout(Duration totalTimeout) {
            this.totalTimeout = totalTimeout;
        }

        /** The mandatory, org-wide corporate section source (architecture §5). */
        public static class Corporate {
            private String project;
            private String ref = "main";
            private String basePromptPath = "prompts/base-system-prompt.md";
            private String reviewRulesPath = "prompts/review-rules.md";

            public String getProject() {
                return project;
            }

            public void setProject(String project) {
                this.project = project;
            }

            public String getRef() {
                return ref;
            }

            public void setRef(String ref) {
                this.ref = ref;
            }

            public String getBasePromptPath() {
                return basePromptPath;
            }

            public void setBasePromptPath(String basePromptPath) {
                this.basePromptPath = basePromptPath;
            }

            public String getReviewRulesPath() {
                return reviewRulesPath;
            }

            public void setReviewRulesPath(String reviewRulesPath) {
                this.reviewRulesPath = reviewRulesPath;
            }
        }

        /** The optional, per-reviewed-project section source (architecture §5/§0.1: always the project's own default branch). */
        public static class Project {
            private boolean enabled = true;
            private String architecturePath = ".ai-review/architecture.md";
            private String codeRulesPath = ".ai-review/code-rules.md";
            private Map<String, Override> overrides = new LinkedHashMap<>();

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getArchitecturePath() {
                return architecturePath;
            }

            public void setArchitecturePath(String architecturePath) {
                this.architecturePath = architecturePath;
            }

            public String getCodeRulesPath() {
                return codeRulesPath;
            }

            public void setCodeRulesPath(String codeRulesPath) {
                this.codeRulesPath = codeRulesPath;
            }

            public Map<String, Override> getOverrides() {
                return overrides;
            }

            public void setOverrides(Map<String, Override> overrides) {
                this.overrides = overrides != null ? overrides : new LinkedHashMap<>();
            }

            /**
             * Per-project override, keyed by {@code projectId} (as a string map key, per
             * {@code @ConfigurationProperties} binding conventions). {@code ref}/{@code architecturePath}/
             * {@code codeRulesPath} are optional — {@code null} means "use the {@link Project}-level
             * default" (the project's own default branch / the default paths).
             */
            public static class Override {
                private String project;
                private String ref;
                private String architecturePath;
                private String codeRulesPath;

                public String getProject() {
                    return project;
                }

                public void setProject(String project) {
                    this.project = project;
                }

                public String getRef() {
                    return ref;
                }

                public void setRef(String ref) {
                    this.ref = ref;
                }

                public String getArchitecturePath() {
                    return architecturePath;
                }

                public void setArchitecturePath(String architecturePath) {
                    this.architecturePath = architecturePath;
                }

                public String getCodeRulesPath() {
                    return codeRulesPath;
                }

                public void setCodeRulesPath(String codeRulesPath) {
                    this.codeRulesPath = codeRulesPath;
                }
            }
        }

        /** Project-section failure handling; corporate-section failures are always FAIL, not configurable. */
        public static class ErrorHandling {
            private String onError = "FAIL";

            public String getOnError() {
                return onError;
            }

            public void setOnError(String onError) {
                this.onError = onError;
            }
        }

        /** Resolution limits (architecture §5). */
        public static class Limits {
            private int maxFileBytes = 262_144;
            private int maxSystemPromptTokens = 6000;
            private int minDiffBudgetTokens = 1000;
            private int maxSections = 4;
            /** PMR-19: bounded concurrency permit for the whole resolve-block. */
            private int maxConcurrentResolutions = 4;

            public int getMaxFileBytes() {
                return maxFileBytes;
            }

            public void setMaxFileBytes(int maxFileBytes) {
                this.maxFileBytes = maxFileBytes;
            }

            public int getMaxSystemPromptTokens() {
                return maxSystemPromptTokens;
            }

            public void setMaxSystemPromptTokens(int maxSystemPromptTokens) {
                this.maxSystemPromptTokens = maxSystemPromptTokens;
            }

            public int getMinDiffBudgetTokens() {
                return minDiffBudgetTokens;
            }

            public void setMinDiffBudgetTokens(int minDiffBudgetTokens) {
                this.minDiffBudgetTokens = minDiffBudgetTokens;
            }

            public int getMaxSections() {
                return maxSections;
            }

            public void setMaxSections(int maxSections) {
                this.maxSections = maxSections;
            }

            public int getMaxConcurrentResolutions() {
                return maxConcurrentResolutions;
            }

            public void setMaxConcurrentResolutions(int maxConcurrentResolutions) {
                this.maxConcurrentResolutions = maxConcurrentResolutions;
            }
        }
    }
}

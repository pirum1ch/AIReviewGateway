package com.review.gateway.config;

import jakarta.annotation.PostConstruct;
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

    private static final int MIN_SECRET_LENGTH = 32;

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

        validatePromptOnStartup();
    }

    /**
     * PMR-14/PMR-30: {@code gateway.prompt.*} is validated only when the kill-switch
     * ({@code gateway.prompt.enabled}) is on — an operator who has not opted into this feature (the
     * shipped default requires explicit corporate-project configuration) must not have the Gateway
     * refuse to start over an unrelated, unset new config tree; that would break "kill-switch off is
     * identical to today" (PMR-10's own premise). When enabled, every rule below is fail-fast, same
     * {@code @PostConstruct} pattern as SR-15.
     */
    private void validatePromptOnStartup() {
        if (!prompt.isEnabled()) {
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
        if (!PROJECT_REF_PATTERN.matcher(value).matches()) {
            throw new IllegalStateException(
                    propertyName + " must be a numeric project id or a 'group/project'-style path, never a URL "
                            + "(PMR-14) — refusing to start");
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

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    /** Hard job-duration backstop (§9 {@code gateway.job.*}). */
    public static class Job {
        private Duration maxDuration = Duration.ofMinutes(45);

        public Duration getMaxDuration() {
            return maxDuration;
        }

        public void setMaxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
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
        private Duration readTimeout = Duration.ofSeconds(5);
        /**
         * Regex the probed backend's host must match, checked in addition to the always-enforced
         * loopback/link-local/metadata-range block (SR-10). Permissive by default ({@code ".*"} = any
         * host) since deployments vary (private LAN hostnames/IPs for Mac minis); operators should
         * tighten this in production config to their actual backend network.
         */
        private String allowedHostPattern = ".*";

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
        /** Kill-switch: {@code false} = today's Worker-JAR-only behavior, zero GitLab calls (PMR-10). */
        private boolean enabled = true;
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

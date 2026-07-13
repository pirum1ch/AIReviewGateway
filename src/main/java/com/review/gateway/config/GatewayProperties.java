package com.review.gateway.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Typed {@code gateway.*} configuration surface. Extended in feature/03-api-security with the
 * GitLab/backend/security sub-trees and scheduler intervals (§9); the feature/02 fields (diff,
 * heartbeat, retry, job, publish) are unchanged and considered stable.
 *
 * <p>Registered directly via {@code @Component} (rather than {@code @EnableConfigurationProperties}
 * on a separate {@code @Configuration} class) so it is available for constructor injection into
 * services immediately.
 *
 * <p>{@link #validateOnStartup()} enforces SR-01 (the four secrets — CI/Worker/Admin bearer tokens
 * and the GitLab API token — must be present and at least 32 characters; a leaked/misconfigured short
 * token fails the Gateway's startup rather than silently authenticating with a guessable value) and
 * SR-15 (the GitLab base URL must be {@code https}). It runs via {@code @PostConstruct}, so it only
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

    @PostConstruct
    void validateOnStartup() {
        requireSecret("gateway.security.ci-token", security.getCiToken());
        requireSecret("gateway.security.worker-token", security.getWorkerToken());
        requireSecret("gateway.security.admin-token", security.getAdminToken());
        requireSecret("gateway.gitlab.token", gitlab.getToken());

        if (gitlab.getBaseUrl() == null || !gitlab.getBaseUrl().startsWith("https://")) {
            throw new IllegalStateException(
                    "gateway.gitlab.base-url must use https:// (SR-15); got: " + describeUrlScheme(gitlab.getBaseUrl()));
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
         * SR-11 hard edge cap (bytes) for the whole {@code POST /reviews} request body, enforced by
         * {@code RequestBodySizeLimitFilter} before Spring MVC/Jackson reads it. Sized generously above
         * {@code maxDiffTokens * charsPerToken} to allow for JSON-escaping overhead (quotes/backslashes/
         * newlines in a diff can each expand to a 2-6 char escape sequence) and the request's other
         * fields; default here assumes the stock {@code maxDiffTokens=10000}/{@code charsPerToken=4}.
         */
        private long maxRequestBodyBytes = 100_000;

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
        /** Masked by {@link #toString()} — never logged/echoed in plain text (SR-12). */
        private String token;
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
            return "GitLab{baseUrl='" + baseUrl + "', token=" + (token == null ? "null" : "***MASKED***") + "}";
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
}

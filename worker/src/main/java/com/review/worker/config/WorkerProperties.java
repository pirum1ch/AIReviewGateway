package com.review.worker.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Binds the whole {@code gateway/worker/backend/llama/network/heartbeat/prompt} configuration surface
 * (top-level YAML keys, architecture §4.1 — hence no common {@code prefix}; this binds at the
 * configuration root) and fails the application fast on any of the rules in architecture §4.2, as
 * amended by {@code docs/worker-threat-model.md}, which the threat model states explicitly
 * <b>override</b> the architecture doc where they differ:
 *
 * <ul>
 *   <li>WSR-01: {@code promptVersion} allowlist validation lives in {@code PromptTemplateService}, not
 *       here — but the {@code prompt.location} prefix that makes that validation meaningful (WSR-07:
 *       classpath-only, never an operator-writable directory) <em>is</em> enforced here.</li>
 *   <li>WSR-09: {@code worker.allow-insecure-gateway} is honored only when {@code gateway.url}'s host is
 *       loopback; a non-loopback host with a plain {@code http://} URL always fails startup, flag or
 *       not.</li>
 *   <li>WSR-12: {@code management.server.address} must resolve to a loopback address; a blank or
 *       off-loopback value fails startup (this is the one rule here that reads a property outside the
 *       {@code gateway/worker/.../prompt} tree, via a direct {@code @Value} injection).</li>
 * </ul>
 *
 * <p>Basic per-field constraints (non-blank, positive) are also declared as JSR-380 annotations
 * (enforced by {@code spring-boot-starter-validation} because this class is {@code @Validated}); the
 * cross-field/business rules that annotations cannot express (scheme + loopback exceptions, the
 * heartbeat-vs-staleness relationship, the classpath-only prompt location, the management-address
 * check) live in {@link #validateOnStartup()}, mirroring the Gateway's own
 * {@code GatewayProperties.validateOnStartup()} pattern. Every failure message names the property only,
 * never its value.
 */
@Component
@ConfigurationProperties
@Validated
public class WorkerProperties {

    private static final Logger log = LoggerFactory.getLogger(WorkerProperties.class);

    private static final int MIN_API_KEY_LENGTH = 32;
    private static final int HEARTBEAT_STALENESS_CEILING_SEC = 180;
    private static final int HEARTBEAT_WARN_THRESHOLD_SEC = 90;
    private static final List<String> LOOPBACK_HOSTS = List.of("localhost", "127.0.0.1", "::1", "[::1]");

    @Valid
    private final Gateway gateway = new Gateway();
    @Valid
    private final Worker worker = new Worker();
    @Valid
    private final Backend backend = new Backend();
    @Valid
    private final Llama llama = new Llama();
    @Valid
    private final Network network = new Network();
    @Valid
    private final Heartbeat heartbeat = new Heartbeat();
    @Valid
    private final Prompt prompt = new Prompt();

    /**
     * Read directly (not part of the {@code gateway/worker/...} tree) so {@link #validateOnStartup()}
     * can enforce WSR-12 (management endpoints must bind to loopback only) without requiring every
     * caller to also inject Spring Boot's own {@code ManagementServerProperties}.
     */
    private final String managementServerAddress;

    public WorkerProperties(@Value("${management.server.address:}") String managementServerAddress) {
        this.managementServerAddress = managementServerAddress;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public Worker getWorker() {
        return worker;
    }

    public Backend getBackend() {
        return backend;
    }

    public Llama getLlama() {
        return llama;
    }

    public Network getNetwork() {
        return network;
    }

    public Heartbeat getHeartbeat() {
        return heartbeat;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    @PostConstruct
    void validateOnStartup() {
        validateGatewayUrl();
        validateLlamaUrl();
        requirePositive("network.pollIntervalMs", network.getPollIntervalMs());
        requirePositive("network.requestTimeoutSec", network.getRequestTimeoutSec());
        requirePositive("network.gatewayTimeoutSec", network.getGatewayTimeoutSec());
        validateHeartbeatInterval();
        requirePositive("worker.limits.maxDiffBytes", worker.getLimits().getMaxDiffBytes());
        requirePositive("worker.limits.maxResponseBytes", worker.getLimits().getMaxResponseBytes());
        validatePromptLocation();
        validateManagementServerAddress();
        warnIfHeapDumpOnOutOfMemoryEnabled();
    }

    private void validateGatewayUrl() {
        String url = gateway.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("gateway.url must be set — refusing to start");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("gateway.url is not a valid URI — refusing to start");
        }
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if (!"http".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("gateway.url must use http:// or https:// — refusing to start");
        }
        // scheme is plain http: only ever acceptable for a loopback host, and only with the explicit flag.
        if (!worker.isAllowInsecureGateway()) {
            throw new IllegalStateException(
                    "gateway.url must use https:// (set worker.allow-insecure-gateway=true only for a "
                            + "loopback gateway.url in dev) — refusing to start");
        }
        if (!isLoopbackHost(uri.getHost())) {
            throw new IllegalStateException(
                    "worker.allow-insecure-gateway is only valid when gateway.url's host is loopback "
                            + "(localhost/127.0.0.1/::1) — refusing to start");
        }
        log.warn("gateway.url uses plain http:// against a loopback host with worker.allow-insecure-gateway=true "
                + "— acceptable for local development only, never in production");
    }

    private void validateLlamaUrl() {
        String url = llama.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("llama.url must be set — refusing to start");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("llama.url is not a valid URI — refusing to start");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException("llama.url must use http:// or https:// — refusing to start");
        }
        // WSR-06 (SHOULD): a non-loopback llama URL is a warn, not a hard fail -- the llama socket is
        // unauthenticated by design (architecture §13/threat-model WA5); non-loopback deployments are
        // unusual but not forbidden, so this is surfaced rather than silently accepted.
        if (!isLoopbackHost(uri.getHost()) && !llama.isAllowNonLoopback()) {
            log.warn("llama.url host is not loopback (127.0.0.1/::1/localhost) and llama.allow-non-loopback "
                    + "is not set — the llama-server endpoint is unauthenticated; confirm this is intentional "
                    + "(WSR-06)");
        }
    }

    private void validateHeartbeatInterval() {
        int intervalSec = heartbeat.getIntervalSec();
        if (intervalSec <= 0) {
            throw new IllegalStateException("heartbeat.intervalSec must be > 0 — refusing to start");
        }
        if (intervalSec >= HEARTBEAT_STALENESS_CEILING_SEC) {
            throw new IllegalStateException(
                    "heartbeat.intervalSec must be < " + HEARTBEAT_STALENESS_CEILING_SEC
                            + " (the Gateway's stale-heartbeat timeout) or every job would self-evict — refusing to start");
        }
        if (intervalSec > HEARTBEAT_WARN_THRESHOLD_SEC) {
            log.warn("heartbeat.intervalSec={} leaves little margin below the Gateway's {}s staleness timeout "
                    + "— consider a lower value", intervalSec, HEARTBEAT_STALENESS_CEILING_SEC);
        }
    }

    private void validatePromptLocation() {
        String location = prompt.getLocation();
        if (location == null || !location.startsWith("classpath:")) {
            // WSR-07: templates must ship only on the classpath inside the fat JAR; there must be no
            // code path (or even a reachable configuration) that resolves a template from an
            // operator-writable filesystem directory.
            throw new IllegalStateException("prompt.location must start with 'classpath:' — refusing to start");
        }
    }

    private void validateManagementServerAddress() {
        // WSR-12: fail fast unless management endpoints are explicitly bound to loopback -- a blank
        // value (Spring's default: bind to the same address as the main server, i.e. all interfaces)
        // or an off-loopback value would expose /actuator/prometheus and /actuator/health beyond the host.
        if (managementServerAddress == null || managementServerAddress.isBlank()) {
            throw new IllegalStateException(
                    "management.server.address must be set to a loopback address (127.0.0.1) — refusing to start");
        }
        if (!isLoopbackHost(managementServerAddress.trim())) {
            throw new IllegalStateException(
                    "management.server.address must be a loopback address (127.0.0.1/::1/localhost) — refusing to start");
        }
    }

    /**
     * Best-effort, WARN-only (not fail-fast: WSR-16 is a SHOULD, and this is a packaging/launch-flag
     * concern the Worker process cannot itself change once the JVM has already started). Confidentiality
     * of the in-memory-only diff/token relies on no heap dump ever being written to disk (WT-10).
     */
    private void warnIfHeapDumpOnOutOfMemoryEnabled() {
        try {
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            boolean heapDumpEnabled = jvmArgs.stream()
                    .anyMatch(arg -> arg.toLowerCase(Locale.ROOT).contains("+heapdumponoutofmemoryerror"));
            if (heapDumpEnabled) {
                log.warn("JVM flag -XX:+HeapDumpOnOutOfMemoryError is enabled -- an OOM would write the diff/"
                        + "token-bearing heap to disk (WT-10). Launch with -XX:-HeapDumpOnOutOfMemoryError, "
                        + "or restrict -XX:HeapDumpPath to a 0700 dir on an encrypted volume.");
            }
        } catch (RuntimeException e) {
            // Never fail startup over a diagnostic-only check.
            log.debug("Could not inspect JVM input arguments for HeapDumpOnOutOfMemoryError", e);
        }
    }

    private void requirePositive(String propertyName, long value) {
        if (value <= 0) {
            throw new IllegalStateException(propertyName + " must be > 0 — refusing to start");
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return LOOPBACK_HOSTS.contains(normalized) || normalized.startsWith("127.");
    }

    public static class Gateway {
        @NotBlank
        private String url;
        @NotBlank
        private String apiKey;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    public static class Worker {
        @NotBlank
        private String id;
        private String version = "1.0.0";
        /** WSR-09: dev-only escape hatch, valid only when {@code gateway.url}'s host is loopback. */
        private boolean allowInsecureGateway = false;
        @Valid
        private final Limits limits = new Limits();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public boolean isAllowInsecureGateway() {
            return allowInsecureGateway;
        }

        public void setAllowInsecureGateway(boolean allowInsecureGateway) {
            this.allowInsecureGateway = allowInsecureGateway;
        }

        public Limits getLimits() {
            return limits;
        }

        /** WSR-03/WSR-04: independent Worker-side caps on Gateway-/llama-supplied data (treated as untrusted). */
        public static class Limits {
            /**
             * Hard cap on the claimed diff, in bytes. Defaulted generously above the Gateway's own
             * diff-size budget (~40,000 chars at its default {@code chars-per-token=4} /
             * {@code max-diff-tokens=10000}) so normal operation never trips it; it exists purely as a
             * defensive bound against a misbehaving/compromised Gateway or a MITM (WSR-03).
             */
            private long maxDiffBytes = 262_144L;
            /**
             * Hard cap on the llama response body, in bytes. Deliberately set to match the Gateway's own
             * documented "normal" raw-response ceiling ({@code gateway.publish.max-raw-response-length},
             * 200,000 characters) rather than an arbitrary figure, and stays safely below the Gateway's
             * true edge cap for the whole {@code POST /jobs/{id}/result} body (500,000 bytes,
             * {@code gateway.publish.max-request-body-bytes}) even after JSON-escaping overhead (WSR-04).
             */
            private long maxResponseBytes = 200_000L;

            public long getMaxDiffBytes() {
                return maxDiffBytes;
            }

            public void setMaxDiffBytes(long maxDiffBytes) {
                this.maxDiffBytes = maxDiffBytes;
            }

            public long getMaxResponseBytes() {
                return maxResponseBytes;
            }

            public void setMaxResponseBytes(long maxResponseBytes) {
                this.maxResponseBytes = maxResponseBytes;
            }
        }
    }

    public static class Backend {
        @NotBlank
        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    public static class Llama {
        @NotBlank
        private String url = "http://127.0.0.1:8000";
        @NotBlank
        private String model;
        private double temperature = 0.1;
        @Positive
        private int maxTokens = 4096;
        /** WSR-06: suppresses the non-loopback warning for an intentional non-loopback deployment. */
        private boolean allowNonLoopback = false;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public boolean isAllowNonLoopback() {
            return allowNonLoopback;
        }

        public void setAllowNonLoopback(boolean allowNonLoopback) {
            this.allowNonLoopback = allowNonLoopback;
        }
    }

    public static class Network {
        @Positive
        private long pollIntervalMs = 3000L;
        @Positive
        private int requestTimeoutSec = 1800;
        @Positive
        private int gatewayTimeoutSec = 10;

        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        public int getRequestTimeoutSec() {
            return requestTimeoutSec;
        }

        public void setRequestTimeoutSec(int requestTimeoutSec) {
            this.requestTimeoutSec = requestTimeoutSec;
        }

        public int getGatewayTimeoutSec() {
            return gatewayTimeoutSec;
        }

        public void setGatewayTimeoutSec(int gatewayTimeoutSec) {
            this.gatewayTimeoutSec = gatewayTimeoutSec;
        }
    }

    public static class Heartbeat {
        private int intervalSec = 60;

        public int getIntervalSec() {
            return intervalSec;
        }

        public void setIntervalSec(int intervalSec) {
            this.intervalSec = intervalSec;
        }
    }

    public static class Prompt {
        @NotBlank
        private String location = "classpath:prompts/";

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }
    }
}

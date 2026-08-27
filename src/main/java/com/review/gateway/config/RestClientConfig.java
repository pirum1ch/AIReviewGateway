package com.review.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.function.Supplier;

/**
 * Two {@code RestClient} beans (not the deprecated {@code RestTemplate}), per architecture §11/§7:
 * {@code gitLabRestClient} (fixed, admin-configured base URL + token) and
 * {@code backendProbeRestClientFactory} (a {@code Supplier}, not a shared instance — see its own javadoc;
 * per-backend URL supplied at call time by {@code BackendProberImpl}), SSRF-hardened at the transport
 * layer — SR-10: redirects are disabled here
 * so a compromised/malicious backend cannot redirect the probe to an internal target the allowlist
 * would otherwise reject).
 */
@Configuration
public class RestClientConfig {

    private final GatewayProperties properties;

    public RestClientConfig(GatewayProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RestClient gitLabRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getGitlab().getConnectTimeout())
                // F-PM-10: pinned explicitly, matching gitLabPromptRestClient/backendProbeRestClient
                // (PMR-16/threat model §4.4 asks for it on both GitLab clients) -- the JDK's own
                // HttpClient.Builder default happens to be NEVER, so behavior is unchanged, but this
                // must be a stated contract, not an inherited default one edit away from silently
                // forwarding PRIVATE-TOKEN (a custom header the JDK does not strip on redirect) off-host.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getGitlab().getReadTimeout());

        return RestClient.builder()
                .baseUrl(properties.getGitlab().getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("PRIVATE-TOKEN", properties.getGitlab().getToken())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /**
     * Prompt Manager's dedicated read-only GitLab client (PMR-15/PMR-16): a separate {@code
     * PRIVATE-TOKEN} ({@code GITLAB_PROMPT_TOKEN}, {@code read_api}/{@code read_repository}-scoped) and
     * separate timeouts (3s connect / 8s read, {@code gateway.prompt.*}) from the write-scoped {@code
     * gitLabRestClient}, so a leak of either credential is bounded to its own blast radius and read-path
     * timeouts never race the (much longer) 5s/30s publish client's. Same host
     * ({@code gateway.gitlab.base-url}) as {@code gitLabRestClient} — there is no separate URL field
     * anywhere in {@code gateway.prompt.*} (PMR-14). {@code followRedirects(NEVER)} set explicitly
     * (PMR-16): a redirect would forward the custom {@code PRIVATE-TOKEN} header off-host, which the JDK
     * client does not strip on its own for non-standard headers.
     */
    @Bean
    public RestClient gitLabPromptRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getPrompt().getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getPrompt().getReadTimeout());

        return RestClient.builder()
                .baseUrl(properties.getGitlab().getBaseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("PRIVATE-TOKEN", properties.getGitlab().getPromptToken())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /**
     * A <b>factory</b>, not a shared client: {@code BackendProberImpl} calls {@code .get()} for a fresh
     * {@code RestClient} on every single probe. A pooled/shared client's keep-alive connection can
     * survive a remote {@code llama-server} restart and come back silently poisoned (the OS-level socket
     * looks fine to the JDK's connection pool but the process on the other end is new) — and unlike a
     * genuinely dead connection, that failure mode does not reliably surface as an exception, so a
     * shared client can under-report backend health indefinitely. A fresh client per probe costs one
     * extra TCP handshake per health check (`gateway.backend.*-interval`, tens of seconds apart) in
     * exchange for never trusting a stale connection's silence.
     */
    @Bean
    public Supplier<RestClient> backendProbeRestClientFactory() {
        return () -> {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(properties.getBackend().getConnectTimeout())
                    // SR-10: never follow a redirect on the probe client -- a malicious/compromised backend
                    // must not be able to redirect this call to an internal target.
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(properties.getBackend().getReadTimeout());

            return RestClient.builder()
                    .requestFactory(requestFactory)
                    .build();
        };
    }
}

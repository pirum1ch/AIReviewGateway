package com.review.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Two {@code RestClient} beans (not the deprecated {@code RestTemplate}), per architecture §11/§7:
 * {@code gitLabRestClient} (fixed, admin-configured base URL + token) and
 * {@code backendProbeRestClient} (per-backend URL supplied at call time by
 * {@code BackendProberImpl}, SSRF-hardened at the transport layer — SR-10: redirects are disabled here
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

    @Bean
    public RestClient backendProbeRestClient() {
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
    }
}

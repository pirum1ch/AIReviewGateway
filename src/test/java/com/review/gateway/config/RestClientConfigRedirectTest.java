package com.review.gateway.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PMR-16/SR-10: {@code gitLabPromptRestClient} (and, pre-existing, {@code backendProbeRestClient}) must
 * have {@code HttpClient.Redirect.NEVER} actually enforced at the transport layer, not just configured —
 * a redirect would forward the custom {@code PRIVATE-TOKEN} header off-host (the JDK client does not
 * strip non-standard headers cross-host the way it does {@code Authorization}).
 *
 * <p>This is a real gap the rest of the suite leaves open: {@code GitLabClientImplTest} exercises
 * {@link com.review.gateway.service.GitLabClientImpl} against {@code MockRestServiceServer}, which
 * intercepts at the {@code ClientHttpRequestFactory} boundary and never actually drives the underlying
 * JDK {@code HttpClient}'s real redirect-following behavior — so {@code followRedirects(NEVER)} being set
 * in {@link RestClientConfig} has never actually been proven to take effect. This test builds the exact
 * same {@code RestClient} the production bean method builds (real {@code HttpClient} + real
 * {@code JdkClientHttpRequestFactory}) and points it at a real, in-process HTTP server that issues a real
 * 302, asserting the redirect target is never contacted.
 */
class RestClientConfigRedirectTest {

    private RedirectStubServer target;
    private RedirectStubServer origin;

    @AfterEach
    void tearDown() {
        if (target != null) {
            target.close();
        }
        if (origin != null) {
            origin.close();
        }
    }

    private GatewayProperties propertiesFor(String baseUrl) {
        GatewayProperties properties = new GatewayProperties();
        properties.getGitlab().setBaseUrl(baseUrl);
        properties.getGitlab().setToken("a".repeat(32));
        properties.getGitlab().setPromptToken("b".repeat(32));
        properties.getGitlab().setConnectTimeout(Duration.ofSeconds(2));
        properties.getGitlab().setReadTimeout(Duration.ofSeconds(2));
        properties.getPrompt().setConnectTimeout(Duration.ofSeconds(2));
        properties.getPrompt().setReadTimeout(Duration.ofSeconds(2));
        return properties;
    }

    @Test
    void gitLabPromptRestClientNeverFollowsA302ToAnotherHost() {
        target = new RedirectStubServer();
        origin = new RedirectStubServer();
        target.respondPlainly("/secret", 200, "you should never see this");
        origin.redirectTo("/projects/1", target.baseUrl() + "/secret");

        GatewayProperties properties = propertiesFor(origin.baseUrl());
        RestClient client = new RestClientConfig(properties).gitLabPromptRestClient();

        int status = client.get().uri("/projects/1").exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(302); // the raw redirect response itself, not a followed 200
        assertThat(target.wasEverContacted()).isFalse();
    }

    @Test
    void gitLabRestClientAlsoNeverFollowsA302ToAnotherHost() {
        // PMR-16 only mandates this explicitly for the new prompt client, but gitLabRestClient carries the
        // write-scoped token and deserves the same proof -- it currently relies on the JDK's own NEVER
        // default rather than an explicit setting (architecture §4.4's own caveat), so this also guards
        // against that default silently changing.
        target = new RedirectStubServer();
        origin = new RedirectStubServer();
        target.respondPlainly("/secret", 200, "you should never see this");
        origin.redirectTo("/projects/1/merge_requests/2/discussions", target.baseUrl() + "/secret");

        GatewayProperties properties = propertiesFor(origin.baseUrl());
        RestClient client = new RestClientConfig(properties).gitLabRestClient();

        int status = client.get().uri("/projects/1/merge_requests/2/discussions")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(302);
        assertThat(target.wasEverContacted()).isFalse();
    }

    /** Minimal in-process HTTP server (JDK {@code HttpServer}, no new test dependency). */
    private static final class RedirectStubServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicBoolean contacted = new AtomicBoolean(false);
        private volatile String redirectFromPath;
        private volatile String redirectLocation;
        private volatile String plainPath;
        private volatile int plainStatus;
        private volatile String plainBody;

        RedirectStubServer() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("Could not start the redirect stub server", e);
            }
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void redirectTo(String fromPath, String location) {
            this.redirectFromPath = fromPath;
            this.redirectLocation = location;
        }

        void respondPlainly(String path, int status, String body) {
            this.plainPath = path;
            this.plainStatus = status;
            this.plainBody = body;
        }

        boolean wasEverContacted() {
            return contacted.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            contacted.set(true);
            String path = exchange.getRequestURI().getPath();
            if (path.equals(redirectFromPath)) {
                exchange.getResponseHeaders().set("Location", redirectLocation);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            if (path.equals(plainPath)) {
                byte[] bytes = plainBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(plainStatus, bytes.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

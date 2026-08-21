package com.review.gateway.service;

import com.review.gateway.service.dto.DiffRefs;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent QA verification of DPR-07's "never throws" guarantee against the REAL production HTTP
 * stack ({@code JdkClientHttpRequestFactory}/{@code java.net.http.HttpClient}, exactly as {@code
 * RestClientConfig.gitLabRestClient()} builds it) — not {@code MockRestServiceServer}, which intercepts
 * before any real socket I/O and therefore never proves the actual {@code ConnectException}/{@code
 * HttpTimeoutException} translation path the developer's own {@code GitLabClientImplTest} relies on
 * (its "connection reset" case throws a synthetic {@code IOException} from inside the mock server's
 * response-writing callback, not from a real socket). Two scenarios a fake server cannot reproduce:
 * a genuinely closed TCP port (connection refused) and a genuinely accepted-but-silent socket (read
 * timeout) against the real client's configured timeout.
 */
class GitLabClientImplRealNetworkFailureTest {

    private GitLabClientImpl clientFor(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        RestClient realClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("PRIVATE-TOKEN", "test-token-0123456789012345678901234567")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
        // gitLabPromptRestClient is never touched by fetchDiffRefs -- reuse the same instance, unused.
        return new GitLabClientImpl(realClient, realClient, new TextSanitizer(), new MetricsCounters());
    }

    @Test
    void fetchDiffRefsOnAGenuinelyClosedPortIsEmptyNeverThrows() throws IOException {
        // Bind then immediately close a real server socket -- the OS reliably refuses the next connect
        // to this now-closed local port (unlike a firewalled/unreachable host, which would instead time
        // out and duplicate the timeout scenario below).
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        GitLabClientImpl client = clientFor("http://127.0.0.1:" + closedPort, Duration.ofSeconds(2), Duration.ofSeconds(2));

        Optional<DiffRefs> result = client.fetchDiffRefs(1L, 5L);

        assertThat(result).as("a real connection-refused failure must degrade to empty, never throw").isEmpty();
    }

    @Test
    void fetchDiffRefsOnAnAcceptedButSilentSocketTimesOutAndIsEmptyNeverThrows() throws Exception {
        // A real server socket that accepts the TCP connection but never writes a response -- exercises
        // the real read-timeout path (JdkClientHttpRequestFactory.setReadTimeout / HttpTimeoutException),
        // not merely a fast connection failure.
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        ExecutorService acceptor = Executors.newSingleThreadExecutor();
        acceptor.submit(() -> {
            try (Socket accepted = serverSocket.accept()) {
                // Deliberately never read the request or write a response -- hold the connection open
                // past the client's read timeout below, then let the try-with-resources close it.
                Thread.sleep(3000);
            } catch (Exception ignored) {
                // Test teardown races (socket closed under us) are expected and harmless here.
            }
        });

        try {
            GitLabClientImpl client = clientFor("http://127.0.0.1:" + port, Duration.ofSeconds(2), Duration.ofMillis(300));

            Optional<DiffRefs> result = client.fetchDiffRefs(1L, 5L);

            assertThat(result).as("a real read-timeout failure must degrade to empty, never throw").isEmpty();
        } finally {
            acceptor.shutdownNow();
            acceptor.awaitTermination(2, TimeUnit.SECONDS);
            serverSocket.close();
        }
    }
}

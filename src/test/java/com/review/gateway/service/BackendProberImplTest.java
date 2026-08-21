package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BackendProberImplTest {

    private MockRestServiceServer mockServer;
    private BackendProberImpl prober;
    private GatewayProperties properties;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        properties = new GatewayProperties();
        RestClient mockBoundClient = builder.build();
        prober = new BackendProberImpl(() -> new ProbeClient(mockBoundClient, () -> { }), properties);
    }

    private Backend backendWithUrl(String url) {
        return new Backend("test-backend", url, "model-x", 1);
    }

    @Test
    void healthyBackendProbeSucceeds() {
        Backend backend = backendWithUrl("http://192.168.1.60:8080");
        mockServer.expect(requestTo("http://192.168.1.60:8080/health")).andRespond(withSuccess());

        assertThatCode(() -> prober.probe(backend)).doesNotThrowAnyException();
        mockServer.verify();
    }

    @Test
    void serverErrorThrowsBackendUnavailable() {
        Backend backend = backendWithUrl("http://192.168.1.61:8080");
        mockServer.expect(requestTo("http://192.168.1.61:8080/health")).andRespond(withServerError());

        assertThatThrownBy(() -> prober.probe(backend)).isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void ssrfTargetIsRejectedBeforeAnyHttpCall() {
        Backend backend = backendWithUrl("http://169.254.169.254/latest/meta-data");

        assertThatThrownBy(() -> prober.probe(backend)).isInstanceOf(BackendUnavailableException.class);
        // No expectations were set on mockServer, so verify() would fail here if an HTTP call had been
        // attempted at all -- but since none were configured, the absence of any request is implicit
        // given MockRestServiceServer throws AssertionError on an unexpected call before we even get here.
    }

    @Test
    void hostNotMatchingConfiguredAllowlistIsRejected() {
        properties.getBackend().setAllowedHostPattern("^10\\..*");
        Backend backend = backendWithUrl("http://192.168.1.60:8080");

        assertThatThrownBy(() -> prober.probe(backend)).isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void everyProbeRequestsAFreshClientFromTheFactory() {
        // A shared/pooled RestClient here would let a keep-alive connection to one backend's llama-server
        // outlive that process across a restart and get silently reused once dead -- observed live: a
        // backend stuck SUSPECT for 40+ minutes while directly reachable by curl, recovering only once the
        // Gateway itself restarted and discarded its connection pool. Guards that BackendProberImpl never
        // caches the client across calls -- each probe() must ask the factory again.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer countingMockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        AtomicInteger factoryCalls = new AtomicInteger();
        BackendProberImpl countingProber = new BackendProberImpl(() -> {
            factoryCalls.incrementAndGet();
            return new ProbeClient(client, () -> { });
        }, properties);
        Backend backend = backendWithUrl("http://192.168.1.62:8080");
        countingMockServer.expect(requestTo("http://192.168.1.62:8080/health")).andRespond(withSuccess());
        countingMockServer.expect(requestTo("http://192.168.1.62:8080/health")).andRespond(withSuccess());

        countingProber.probe(backend);
        countingProber.probe(backend);

        assertThat(factoryCalls.get()).isEqualTo(2);
    }

    @Test
    void probeClientResourceIsClosedAfterASuccessfulProbe() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        AtomicInteger closeCalls = new AtomicInteger();
        BackendProberImpl closeTrackingProber = new BackendProberImpl(
                () -> new ProbeClient(client, closeCalls::incrementAndGet), properties);
        Backend backend = backendWithUrl("http://192.168.1.63:8080");
        server.expect(requestTo("http://192.168.1.63:8080/health")).andRespond(withSuccess());

        closeTrackingProber.probe(backend);

        assertThat(closeCalls.get()).isEqualTo(1);
    }

    @Test
    void probeClientResourceIsClosedEvenWhenTheProbeFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        AtomicInteger closeCalls = new AtomicInteger();
        BackendProberImpl closeTrackingProber = new BackendProberImpl(
                () -> new ProbeClient(client, closeCalls::incrementAndGet), properties);
        Backend backend = backendWithUrl("http://192.168.1.64:8080");
        server.expect(requestTo("http://192.168.1.64:8080/health")).andRespond(withServerError());

        assertThatThrownBy(() -> closeTrackingProber.probe(backend)).isInstanceOf(BackendUnavailableException.class);

        assertThat(closeCalls.get()).isEqualTo(1);
    }
}

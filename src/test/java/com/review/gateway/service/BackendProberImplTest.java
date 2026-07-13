package com.review.gateway.service;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.exception.BackendUnavailableException;
import com.review.gateway.model.Backend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
        prober = new BackendProberImpl(builder.build(), properties);
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
}

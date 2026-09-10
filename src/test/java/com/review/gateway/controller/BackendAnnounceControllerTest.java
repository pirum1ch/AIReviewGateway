package com.review.gateway.controller;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.config.SecurityConfig;
import com.review.gateway.dto.AnnounceBackendResponse;
import com.review.gateway.exception.BackendNameTakenException;
import com.review.gateway.exception.BackendRegistryFullException;
import com.review.gateway.exception.BackendUrlRejectedException;
import com.review.gateway.service.BackendRegistryService;
import com.review.gateway.service.MetricsCounters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /backends/announce} (WORKER role, Backend Self-Registration architecture §2.2). Loaded
 * only with {@code gateway.backend.self-registration.enabled=true} ({@code @ConditionalOnProperty} on
 * the controller itself) -- {@link com.review.gateway.SecurityMatrixTest} covers the disabled default.
 */
@WebMvcTest(controllers = BackendAnnounceController.class)
@Import({SecurityConfig.class, GatewayProperties.class})
@TestPropertySource(properties = {
        "gateway.backend.self-registration.enabled=true",
        "gateway.backend.allowed-host-pattern=^192\\.168\\..*"
})
class BackendAnnounceControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private BackendRegistryService backendRegistryService;
    @MockitoBean
    private MetricsCounters metricsCounters;

    private static final String VALID_BODY = """
            {"backendId":"mac-mini-01","workerId":"worker-1","url":"http://192.168.1.50:8080","model":"model-x"}
            """;

    @Test
    void announceRequiresWorkerRole() throws Exception {
        mockMvc.perform(post("/backends/announce")
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/backends/announce")
                        .header("Authorization", "Bearer " + SecurityTestTokens.ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void announceReturns200WithTheServiceResponseForWorker() throws Exception {
        when(backendRegistryService.announce("mac-mini-01", "worker-1", "http://192.168.1.50:8080", "model-x"))
                .thenReturn(new AnnounceBackendResponse("mac-mini-01", "ACTIVE", true));

        mockMvc.perform(post("/backends/announce")
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("mac-mini-01"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.created").value(true));
    }

    @Test
    void nameTakenMapsTo409() throws Exception {
        when(backendRegistryService.announce(any(), any(), any(), any()))
                .thenThrow(new BackendNameTakenException("name taken"));

        mockMvc.perform(post("/backends/announce")
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BACKEND_NAME_TAKEN"));
    }

    @Test
    void urlRejectedMapsTo422WithTheFixedMessage() throws Exception {
        when(backendRegistryService.announce(any(), any(), any(), any()))
                .thenThrow(new BackendUrlRejectedException("Backend URL was rejected"));

        mockMvc.perform(post("/backends/announce")
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BACKEND_URL_REJECTED"))
                .andExpect(jsonPath("$.message").value("Backend URL was rejected"));
    }

    @Test
    void registryFullMapsTo503NotFatalToAWorker() throws Exception {
        // F-BSR-04: a full registry is a transient Gateway-side capacity condition, not a permanent
        // Worker-side misconfiguration -- it must not share BACKEND_URL_REJECTED's 422 (which the Worker
        // treats as fail-fast-forever), so the Worker's retry-with-backoff bucket picks it up instead.
        when(backendRegistryService.announce(any(), any(), any(), any()))
                .thenThrow(new BackendRegistryFullException("Backend registry is at its configured capacity"));

        mockMvc.perform(post("/backends/announce")
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("BACKEND_REGISTRY_FULL"));
    }

    @Test
    void malformedBodyIncrementsTheValidationRejectedCounterAndReturns400() throws Exception {
        mockMvc.perform(post("/backends/announce")
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"backendId\":\"\",\"workerId\":\"worker-1\",\"url\":\"http://192.168.1.50:8080\",\"model\":\"model-x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));

        org.mockito.Mockito.verify(metricsCounters).incrementBackendAnnounceRejected("VALIDATION");
    }

    @Test
    void announceIs401WithoutAToken() throws Exception {
        mockMvc.perform(post("/backends/announce")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }
}

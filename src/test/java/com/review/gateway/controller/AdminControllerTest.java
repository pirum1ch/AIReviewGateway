package com.review.gateway.controller;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.config.SecurityConfig;
import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.service.BackendRegistryService;
import com.review.gateway.service.StatisticsService;
import com.review.gateway.service.dto.BackendSnapshot;
import com.review.gateway.service.dto.BackendUpsertOutcome;
import com.review.gateway.service.dto.MetricsSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminController.class)
@Import({SecurityConfig.class, GatewayProperties.class})
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private StatisticsService statisticsService;
    @MockitoBean
    private BackendRegistryService backendRegistryService;

    @Test
    void listBackendsRequiresAdmin() throws Exception {
        when(statisticsService.listBackends()).thenReturn(List.of(
                new BackendSnapshot(1L, "mac-mini-1", "model-x", 2, BackendStatus.ACTIVE, 1L, Instant.now(), null, null)));

        mockMvc.perform(get("/backends").header("Authorization", "Bearer " + SecurityTestTokens.ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("mac-mini-1"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void listBackendsRejectsCiToken() throws Exception {
        mockMvc.perform(get("/backends").header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void listBackendsRejectsWorkerToken() throws Exception {
        mockMvc.perform(get("/backends").header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void metricsRequiresAdmin() throws Exception {
        Map<ReviewStatus, Long> byStatus = new EnumMap<>(ReviewStatus.class);
        byStatus.put(ReviewStatus.QUEUED, 3L);
        when(statisticsService.computeMetrics())
                .thenReturn(new MetricsSnapshot(10, byStatus, 100.0, 200.0, 5, 1, 2, 1, Map.of(), 0, 0, Map.of(), Map.of(), 0, Map.of(), Map.of(), 0));

        mockMvc.perform(get("/metrics").header("Authorization", "Bearer " + SecurityTestTokens.ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.byStatus.QUEUED").value(3))
                .andExpect(jsonPath("$.promptDisabledCount").value(2))
                .andExpect(jsonPath("$.promptSectionMissingCount").value(1))
                // Prompt Manager kill-switch off in the shared test application.yml (PMR-10 premise).
                .andExpect(jsonPath("$.promptManagerEnabled").value(false));
    }

    @Test
    void metricsRejectsCiToken() throws Exception {
        mockMvc.perform(get("/metrics").header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpointsReturn401WithoutAToken() throws Exception {
        mockMvc.perform(get("/backends")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/metrics")).andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------- POST /backends (Backend Self-Reg §2.3) ---

    private Backend backendEntity(String name) {
        return new Backend(name, "http://192.168.1.50:8080", "model-x", 1);
    }

    @Test
    void upsertBackendRequiresAdmin() throws Exception {
        mockMvc.perform(post("/backends")
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mac-mini-01\",\"url\":\"http://192.168.1.50:8080\",\"model\":\"model-x\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/backends")
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mac-mini-01\",\"url\":\"http://192.168.1.50:8080\",\"model\":\"model-x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void upsertBackendReturns201OnCreateAndNeverEchoesUrl() throws Exception {
        when(backendRegistryService.upsertByAdmin(any()))
                .thenReturn(new BackendUpsertOutcome(backendEntity("mac-mini-01"), true));
        when(statisticsService.snapshotOf(any()))
                .thenReturn(new BackendSnapshot(1L, "mac-mini-01", "model-x", 1, BackendStatus.ACTIVE, 0L, null, null, null));

        mockMvc.perform(post("/backends")
                        .header("Authorization", "Bearer " + SecurityTestTokens.ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mac-mini-01\",\"url\":\"http://192.168.1.50:8080\",\"model\":\"model-x\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("mac-mini-01"))
                .andExpect(jsonPath("$.url").doesNotExist());
    }

    @Test
    void upsertBackendReturns200OnUpdate() throws Exception {
        when(backendRegistryService.upsertByAdmin(any()))
                .thenReturn(new BackendUpsertOutcome(backendEntity("mac-mini-01"), false));
        when(statisticsService.snapshotOf(any()))
                .thenReturn(new BackendSnapshot(1L, "mac-mini-01", "model-x", 1, BackendStatus.ACTIVE, 0L, null, null, null));

        mockMvc.perform(post("/backends")
                        .header("Authorization", "Bearer " + SecurityTestTokens.ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mac-mini-01\"}"))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------- DELETE /backends/{name} (Backend Self-Reg §5) ---

    @Test
    void decommissionBackendRequiresAdmin() throws Exception {
        mockMvc.perform(delete("/backends/mac-mini-01")
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void decommissionBackendReturns200WithViewAndNoUrl() throws Exception {
        when(backendRegistryService.decommission(eq("mac-mini-01"))).thenReturn(Optional.of(backendEntity("mac-mini-01")));
        when(statisticsService.snapshotOf(any()))
                .thenReturn(new BackendSnapshot(1L, "mac-mini-01", "model-x", 1, BackendStatus.OFFLINE, 0L, null, null, null));

        mockMvc.perform(delete("/backends/mac-mini-01").header("Authorization", "Bearer " + SecurityTestTokens.ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.url").doesNotExist());
    }

    @Test
    void decommissionUnknownBackendReturns404() throws Exception {
        when(backendRegistryService.decommission(eq("does-not-exist"))).thenReturn(Optional.empty());

        mockMvc.perform(delete("/backends/does-not-exist").header("Authorization", "Bearer " + SecurityTestTokens.ADMIN_TOKEN))
                .andExpect(status().isNotFound());
    }
}

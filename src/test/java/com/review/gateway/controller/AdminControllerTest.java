package com.review.gateway.controller;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.config.SecurityConfig;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.service.StatisticsService;
import com.review.gateway.service.dto.BackendSnapshot;
import com.review.gateway.service.dto.MetricsSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminController.class)
@Import({SecurityConfig.class, GatewayProperties.class})
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private StatisticsService statisticsService;

    @Test
    void listBackendsRequiresAdmin() throws Exception {
        when(statisticsService.listBackends()).thenReturn(List.of(
                new BackendSnapshot(1L, "mac-mini-1", "model-x", 2, BackendStatus.ACTIVE, 1L, Instant.now())));

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
        when(statisticsService.computeMetrics()).thenReturn(new MetricsSnapshot(10, byStatus, 100.0, 200.0, 5, 1));

        mockMvc.perform(get("/metrics").header("Authorization", "Bearer " + SecurityTestTokens.ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.byStatus.QUEUED").value(3));
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
}

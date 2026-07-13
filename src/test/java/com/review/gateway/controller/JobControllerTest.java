package com.review.gateway.controller;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.config.SecurityConfig;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.service.QueueManager;
import com.review.gateway.service.dto.ClaimedJob;
import com.review.gateway.service.dto.HeartbeatResult;
import com.review.gateway.service.dto.SubmitResultCommand;
import com.review.gateway.service.dto.SubmitResultOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JobController.class)
@Import({SecurityConfig.class, GatewayProperties.class})
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private QueueManager queueManager;

    @Test
    void claimReturns200WithPayloadWhenAJobIsAvailable() throws Exception {
        when(queueManager.claim(eq("mac-mini-1"), eq("worker-1")))
                .thenReturn(Optional.of(new ClaimedJob(10L, 20L, "diff content", "v1")));

        mockMvc.perform(post("/jobs/claim")
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"backendId":"mac-mini-1","workerId":"worker-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(10))
                .andExpect(jsonPath("$.reviewId").value(20))
                .andExpect(jsonPath("$.payload.diff").value("diff content"));
    }

    @Test
    void claimReturns204WhenNothingToClaim() throws Exception {
        when(queueManager.claim(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/jobs/claim")
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"backendId":"mac-mini-1","workerId":"worker-1"}
                                """))
                .andExpect(status().isNoContent());
    }

    @Test
    void claimRequiresWorkerRoleNotCi() throws Exception {
        mockMvc.perform(post("/jobs/claim")
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"backendId":"mac-mini-1","workerId":"worker-1"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void heartbeatReturns200WithShouldContinue() throws Exception {
        when(queueManager.heartbeat(eq(10L), eq("worker-1"))).thenReturn(HeartbeatResult.accepted(true));

        mockMvc.perform(post("/jobs/{id}/heartbeat", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shouldContinue").value(true));
    }

    @Test
    void heartbeatReturns404ForUnknownJob() throws Exception {
        when(queueManager.heartbeat(eq(999L), any())).thenReturn(HeartbeatResult.notFound());

        mockMvc.perform(post("/jobs/{id}/heartbeat", 999)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void heartbeatReturns403OnOwnershipMismatch() throws Exception {
        when(queueManager.heartbeat(eq(10L), eq("worker-IMPOSTOR"))).thenReturn(HeartbeatResult.ownershipMismatch());

        mockMvc.perform(post("/jobs/{id}/heartbeat", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-IMPOSTOR"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void submitResultReturns200Idempotently() throws Exception {
        when(queueManager.submitResult(eq(10L), eq("worker-1"), any(SubmitResultCommand.class)))
                .thenReturn(SubmitResultOutcome.idempotentNoop(20L, ReviewStatus.COMPLETED));

        mockMvc.perform(post("/jobs/{id}/result", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","rawResponse":"raw text","promptTokens":10,"completionTokens":5,"durationMs":1000,"model":"model-x"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(20))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void submitResultReturns404ForUnknownJob() throws Exception {
        when(queueManager.submitResult(eq(999L), any(), any())).thenReturn(SubmitResultOutcome.notFound());

        mockMvc.perform(post("/jobs/{id}/result", 999)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","rawResponse":"raw text"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void submitResultReturns403OnOwnershipMismatch() throws Exception {
        when(queueManager.submitResult(eq(10L), eq("worker-IMPOSTOR"), any())).thenReturn(SubmitResultOutcome.ownershipMismatch());

        mockMvc.perform(post("/jobs/{id}/result", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-IMPOSTOR","rawResponse":"raw text"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void jobsEndpointsReturn401WithoutAToken() throws Exception {
        mockMvc.perform(post("/jobs/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"backendId":"mac-mini-1","workerId":"worker-1"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}

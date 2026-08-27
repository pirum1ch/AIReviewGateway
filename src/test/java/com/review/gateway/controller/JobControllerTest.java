package com.review.gateway.controller;

import com.review.gateway.config.GatewayProperties;
import com.review.gateway.config.SecurityConfig;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.service.QueueManager;
import com.review.gateway.service.dto.ClaimedJob;
import com.review.gateway.service.dto.FailureReportOutcome;
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

import java.util.List;
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
                .thenReturn(Optional.of(new ClaimedJob(10L, 20L, "diff content", "v1", null, null, null, null)));

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
    void claimReturns200WithSystemMessagesWhenPromptManagerResolvedSections() throws Exception {
        // Prompt Manager (V3): systemMessages passes through from ClaimedJob into the response payload.
        when(queueManager.claim(eq("mac-mini-1"), eq("worker-1")))
                .thenReturn(Optional.of(new ClaimedJob(10L, 20L, "diff content", "v2", null,
                        List.of("corporate base", "corporate rules"), null, null)));

        mockMvc.perform(post("/jobs/claim")
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"backendId":"mac-mini-1","workerId":"worker-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.systemMessages[0]").value("corporate base"))
                .andExpect(jsonPath("$.payload.systemMessages[1]").value("corporate rules"));
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

    // =================================================================================================
    // POST /jobs/{id}/fail (architecture §5.2, WOC-26..WOC-33, WOR-18)
    // =================================================================================================

    @Test
    void reportFailureReturns200Accepted() throws Exception {
        when(queueManager.reportFailure(eq(10L), eq("worker-1"), eq("LLM_TIMEOUT"), eq("some detail")))
                .thenReturn(FailureReportOutcome.ACCEPTED);

        mockMvc.perform(post("/jobs/{id}/fail", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","reason":"LLM_TIMEOUT","detail":"some detail"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void reportFailureWithoutDetailReturns200Accepted() throws Exception {
        when(queueManager.reportFailure(eq(10L), eq("worker-1"), eq("PROMPT_INVALID"), eq(null)))
                .thenReturn(FailureReportOutcome.ACCEPTED);

        mockMvc.perform(post("/jobs/{id}/fail", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","reason":"PROMPT_INVALID"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void reportFailureReturns404ForUnknownJob() throws Exception {
        when(queueManager.reportFailure(eq(999L), any(), any(), any())).thenReturn(FailureReportOutcome.NOT_FOUND);

        mockMvc.perform(post("/jobs/{id}/fail", 999)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","reason":"LLM_ERROR"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportFailureReturns403OnOwnershipMismatch() throws Exception {
        when(queueManager.reportFailure(eq(10L), eq("worker-IMPOSTOR"), any(), any()))
                .thenReturn(FailureReportOutcome.OWNERSHIP_MISMATCH);

        mockMvc.perform(post("/jobs/{id}/fail", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-IMPOSTOR","reason":"LLM_ERROR"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportFailureResponseNeverCarriesReviewIdOrStatus() throws Exception {
        // WOC-28: deliberately stricter than /result -- no response field ever echoes Review state.
        when(queueManager.reportFailure(eq(10L), eq("worker-1"), any(), any())).thenReturn(FailureReportOutcome.ACCEPTED);

        mockMvc.perform(post("/jobs/{id}/fail", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","reason":"LLM_ERROR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.reviewId").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").doesNotExist());
    }

    // WOR-18: role matrix -- /jobs/{id}/fail is reachable only with the WORKER token.
    @Test
    void reportFailureRequires401WithoutAToken() throws Exception {
        mockMvc.perform(post("/jobs/{id}/fail", 10)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","reason":"LLM_ERROR"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reportFailureRejectsCiToken() throws Exception {
        mockMvc.perform(post("/jobs/{id}/fail", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","reason":"LLM_ERROR"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportFailureRejectsAdminToken() throws Exception {
        mockMvc.perform(post("/jobs/{id}/fail", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","reason":"LLM_ERROR"}
                                """))
                .andExpect(status().isForbidden());
    }

    // =================================================================================================
    // WOR-06: workerId/reason/detail bean-validation on the four Worker-facing DTOs
    // =================================================================================================

    @Test
    void reportFailureRejectsCrlfInWorkerIdWith400() throws Exception {
        mockMvc.perform(post("/jobs/{id}/fail", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"w\\r\\n2026-01-01 INFO forged","reason":"LLM_ERROR"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportFailureRejectsOversizedWorkerIdWith400() throws Exception {
        String oversized = "w".repeat(65);
        mockMvc.perform(post("/jobs/{id}/fail", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"" + oversized + "\",\"reason\":\"LLM_ERROR\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportFailureRejectsOversizedReasonWith400() throws Exception {
        String oversized = "R".repeat(33);
        mockMvc.perform(post("/jobs/{id}/fail", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"worker-1\",\"reason\":\"" + oversized + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportFailureRejectsBlankReasonWith400() throws Exception {
        mockMvc.perform(post("/jobs/{id}/fail", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"worker-1","reason":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void claimRejectsCrlfInWorkerIdWith400() throws Exception {
        mockMvc.perform(post("/jobs/claim")
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"backendId":"mac-mini-1","workerId":"w\\r\\nforged"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void heartbeatRejectsCrlfInWorkerIdWith400() throws Exception {
        mockMvc.perform(post("/jobs/{id}/heartbeat", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"w\\r\\nforged"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void submitResultRejectsCrlfInWorkerIdWith400() throws Exception {
        mockMvc.perform(post("/jobs/{id}/result", 10)
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerId":"w\\r\\nforged","rawResponse":"raw text"}
                                """))
                .andExpect(status().isBadRequest());
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

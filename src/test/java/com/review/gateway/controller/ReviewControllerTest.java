package com.review.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.gateway.config.GatewayProperties;
import com.review.gateway.config.SecurityConfig;
import com.review.gateway.exception.DiffTooLargeException;
import com.review.gateway.exception.InvalidStateTransitionException;
import com.review.gateway.exception.ReviewNotFoundException;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.service.ReviewService;
import com.review.gateway.service.dto.CreateReviewCommand;
import com.review.gateway.service.dto.CreateReviewResult;
import com.review.gateway.service.dto.ReviewStatusView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReviewController.class)
@Import({SecurityConfig.class, GatewayProperties.class})
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private ReviewService reviewService;

    private String validRequestJson() throws Exception {
        return objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("projectId", 1);
            put("mergeRequestId", 2);
            put("headSha", "abc123");
            put("baseSha", "def456");
            put("diff", "some diff content");
            put("promptVersion", "v1");
            put("priority", 10);
        }});
    }

    @Test
    void createReviewReturns201ForANewReview() throws Exception {
        when(reviewService.createReview(any(CreateReviewCommand.class)))
                .thenReturn(new CreateReviewResult(42L, ReviewStatus.QUEUED, false));

        mockMvc.perform(post("/reviews")
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewId").value(42))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void createReviewReturns200WhenDeduplicated() throws Exception {
        when(reviewService.createReview(any(CreateReviewCommand.class)))
                .thenReturn(new CreateReviewResult(7L, ReviewStatus.RUNNING, true));

        mockMvc.perform(post("/reviews")
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(7))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void createReviewReturns422OnDiffTooLarge() throws Exception {
        when(reviewService.createReview(any(CreateReviewCommand.class)))
                .thenThrow(new DiffTooLargeException("too big"));

        mockMvc.perform(post("/reviews")
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("DIFF_TOO_LARGE"));
    }

    @Test
    void createReviewReturns400OnMissingRequiredField() throws Exception {
        String invalidJson = """
                {"projectId": 1, "mergeRequestId": 2, "headSha": "", "baseSha": "d", "diff": "x", "promptVersion": "v1"}
                """;

        mockMvc.perform(post("/reviews")
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void createReviewReturns401WithoutAToken() throws Exception {
        mockMvc.perform(post("/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createReviewReturns403ForWorkerToken() throws Exception {
        mockMvc.perform(post("/reviews")
                        .header("Authorization", "Bearer " + SecurityTestTokens.WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void getStatusReturns200WithBody() throws Exception {
        Instant now = Instant.now();
        when(reviewService.getStatus(42L)).thenReturn(new ReviewStatusView(42L, ReviewStatus.COMPLETED, 1, now, now, 3));

        mockMvc.perform(get("/reviews/{id}", 42)
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewId").value(42))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.commentCount").value(3));
    }

    @Test
    void unexpectedExceptionReturnsGenericFiveHundredBody() throws Exception {
        // SR-17: any unmapped exception must fall through to a fixed, generic body -- never a stack
        // trace or the underlying exception's own message.
        when(reviewService.getStatus(13L)).thenThrow(new IllegalStateException("some internal detail that must never leak"));

        mockMvc.perform(get("/reviews/{id}", 13)
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("internal detail"))));
    }

    @Test
    void getStatusReturns404WhenReviewMissing() throws Exception {
        when(reviewService.getStatus(999L)).thenThrow(new ReviewNotFoundException(999L));

        mockMvc.perform(get("/reviews/{id}", 999)
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void deleteRequiresAdminNotCi() throws Exception {
        mockMvc.perform(delete("/reviews/{id}", 42)
                        .header("Authorization", "Bearer " + SecurityTestTokens.CI_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteWithAdminCancelsAndReturns200() throws Exception {
        Instant now = Instant.now();
        when(reviewService.cancel(42L)).thenReturn(new ReviewStatusView(42L, ReviewStatus.CANCELLED, 1, now, now, 0));

        mockMvc.perform(delete("/reviews/{id}", 42)
                        .header("Authorization", "Bearer " + SecurityTestTokens.ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(reviewService).cancel(42L);
    }

    @Test
    void deleteReturns409WhenAlreadyTerminal() throws Exception {
        when(reviewService.cancel(eq(42L)))
                .thenThrow(new InvalidStateTransitionException(ReviewStatus.PUBLISHED, ReviewStatus.CANCELLED));

        mockMvc.perform(delete("/reviews/{id}", 42)
                        .header("Authorization", "Bearer " + SecurityTestTokens.ADMIN_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE_TRANSITION"));
    }
}

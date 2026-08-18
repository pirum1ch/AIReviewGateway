package com.review.gateway.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SR-11 unit tests for {@link RequestBodySizeLimitFilter}: no test previously exercised this filter
 * directly (only indirectly through configured {@code application.yml} limits, which nothing actually
 * drove close to the cap). Verifies the {@code Content-Length}-based fast-reject for both capped
 * paths ({@code POST /reviews}, {@code POST /jobs/{id}/result}), that other paths/methods are left
 * alone, and that a within-limit request passes through untouched.
 */
class RequestBodySizeLimitFilterTest {

    private GatewayProperties properties;
    private RequestBodySizeLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
        properties.getDiff().setMaxRequestBodyBytes(1000);
        properties.getPublish().setMaxRequestBodyBytes(2000);
        properties.getJob().setMaxFailBodyBytes(500);
        filter = new RequestBodySizeLimitFilter(properties);
    }

    private MockHttpServletResponse runFilter(String method, String uri, long contentLength) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setContent(new byte[(int) contentLength]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);
        return response;
    }

    @Test
    void reviewsPostOverLimitIsRejectedWith413() throws Exception {
        MockHttpServletResponse response = runFilter("POST", "/reviews", 1001);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void reviewsPostAtExactLimitPasses() throws Exception {
        // Boundary: contentLength == limit must NOT be rejected (filter only rejects strictly >).
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/reviews");
        request.setContent(new byte[1000]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void reviewsPostUnderLimitPassesThroughToTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/reviews");
        request.setContent(new byte[500]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void jobResultPostOverLimitIsRejectedWith413() throws Exception {
        MockHttpServletResponse response = runFilter("POST", "/jobs/42/result", 2001);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void jobResultPostUnderLimitPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jobs/42/result");
        request.setContent(new byte[1500]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void jobClaimPostIsNotSizeLimitedByThisFilter() throws Exception {
        // Only /reviews and /jobs/{id}/result have a configured cap here; /jobs/claim and
        // /jobs/{id}/heartbeat bodies are tiny and intentionally uncapped by this filter.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jobs/claim");
        request.setContent(new byte[999999]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void jobHeartbeatPostIsNotSizeLimitedByThisFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jobs/42/heartbeat");
        request.setContent(new byte[999999]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void getRequestsAreNeverSizeLimited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/reviews/1");
        request.setContent(new byte[999999]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void deleteRequestsAreNeverSizeLimited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/reviews/1");
        request.setContent(new byte[999999]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void unrelatedPathIsNeverSizeLimitedEvenIfHuge() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/backends");
        request.setContent(new byte[999999]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectedRequestNeverReachesTheFilterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/reviews");
        request.setContent(new byte[999999]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(Mockito.any(), Mockito.any());
    }

    // =================================================================================================
    // Worker Observability & Claim Latency: WOC-33 (new /jobs/{id}/fail cap), WOR-09 (decoded-path fix)
    // =================================================================================================

    @Test
    void jobFailPostOverLimitIsRejectedWith413() throws Exception {
        MockHttpServletResponse response = runFilter("POST", "/jobs/1/fail", 501);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }

    @Test
    void jobFailPostUnderLimitPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jobs/1/fail");
        request.setContent(new byte[400]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void percentEncodedFailPathCannotBypassTheCap() throws Exception {
        // WOR-09/WOT-06b: "%66ail" decodes to "fail" -- must be capped exactly like the plain path.
        MockHttpServletResponse response = runFilter("POST", "/jobs/1/%66ail", 999_999);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void percentEncodedResultPathCannotBypassTheCap() throws Exception {
        MockHttpServletResponse response = runFilter("POST", "/jobs/1/%72esult", 999_999);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void percentEncodedReviewsPathCannotBypassTheCap() throws Exception {
        MockHttpServletResponse response = runFilter("POST", "/re%76iews", 999_999);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void percentEncodedFailPathUnderLimitStillPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jobs/1/%66ail");
        request.setContent(new byte[400]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectionBodyIsJsonAndCarriesNoInternalDetail() throws Exception {
        MockHttpServletResponse response = runFilter("POST", "/reviews", 999_999);

        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString())
                .contains("\"error\":\"PAYLOAD_TOO_LARGE\"")
                .doesNotContain("Exception")
                .doesNotContain("at com.review");
    }
}

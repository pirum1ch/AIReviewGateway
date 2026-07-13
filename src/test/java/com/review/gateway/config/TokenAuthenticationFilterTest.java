package com.review.gateway.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class TokenAuthenticationFilterTest {

    private static final String CI_TOKEN = "ci-token-0123456789012345678901234567";
    private static final String WORKER_TOKEN = "worker-token-01234567890123456789012";
    private static final String ADMIN_TOKEN = "admin-token-012345678901234567890123";

    private TokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setCiToken(CI_TOKEN);
        properties.getSecurity().setWorkerToken(WORKER_TOKEN);
        properties.getSecurity().setAdminToken(ADMIN_TOKEN);
        filter = new TokenAuthenticationFilter(properties);
        SecurityContextHolder.clearContext();
    }

    private void runFilter(String authorizationHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void ciTokenAuthenticatesAsCiRole() throws Exception {
        runFilter("Bearer " + CI_TOKEN);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_CI");
    }

    @Test
    void workerTokenAuthenticatesAsWorkerRole() throws Exception {
        runFilter("Bearer " + WORKER_TOKEN);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_WORKER");
    }

    @Test
    void adminTokenAuthenticatesAsAdminRole() throws Exception {
        runFilter("Bearer " + ADMIN_TOKEN);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
    }

    @Test
    void unknownTokenLeavesRequestUnauthenticated() throws Exception {
        runFilter("Bearer completely-unknown-token-value");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingAuthorizationHeaderLeavesRequestUnauthenticated() throws Exception {
        runFilter(null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonBearerAuthorizationHeaderIsIgnored() throws Exception {
        runFilter("Basic dXNlcjpwYXNz");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void tokenOfDifferentLengthThanAnyConfiguredTokenIsRejectedWithoutError() throws Exception {
        // Exercises the SHA-256-then-isEqual path with mismatched raw lengths (SR-02): must not throw
        // and must not authenticate, regardless of how the presented token's length compares to the
        // configured tokens' length.
        runFilter("Bearer x");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void emptyBearerTokenIsRejected() throws Exception {
        runFilter("Bearer ");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}

package com.review.gateway.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/** Structural twin of {@link TokenAuthenticationFilterTest} (WHR-01). */
class GitLabWebhookSecretFilterTest {

    private static final String SECRET_1 = "webhook-secret-0123456789012345678901";
    private static final String SECRET_2 = "webhook-secret-rotated-0123456789012345";

    private GitLabWebhookSecretFilter filter;

    @BeforeEach
    void setUp() {
        GatewayProperties properties = new GatewayProperties();
        Set<String> secrets = new LinkedHashSet<>();
        secrets.add(SECRET_1);
        secrets.add(SECRET_2);
        properties.getWebhook().setSecretTokens(secrets);
        filter = new GitLabWebhookSecretFilter(properties);
        SecurityContextHolder.clearContext();
    }

    private void runFilter(String tokenHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (tokenHeader != null) {
            request.addHeader("X-Gitlab-Token", tokenHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void correctTokenAuthenticatesAsWebhookRoleAndNothingElse() throws Exception {
        runFilter(SECRET_1);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_WEBHOOK");
    }

    @Test
    void aSecondRotatedTokenAlsoAuthenticates() throws Exception {
        runFilter(SECRET_2);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void wrongTokenLeavesRequestUnauthenticated() throws Exception {
        runFilter("some-other-value");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingHeaderLeavesRequestUnauthenticated() throws Exception {
        runFilter(null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void filterNeverRejectsItself_alwaysContinuesTheChain() throws Exception {
        // WHR-01: this filter never writes a response itself -- SecurityConfig's .hasRole("WEBHOOK")
        // downstream is what actually produces 401/403.
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200); // untouched by this filter
        verify(chain).doFilter(request, response);
    }
}

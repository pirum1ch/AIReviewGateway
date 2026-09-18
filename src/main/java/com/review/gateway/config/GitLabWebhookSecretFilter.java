package com.review.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * GitLab Webhook Diff Trigger (WHR-01/WHR-02, threat model §4.3): validates the {@code X-Gitlab-Token}
 * header against {@code gateway.webhook.secret-tokens} (a rotation-friendly set, WHR-04) via the
 * constant-time {@link TokenMatcher} shared with {@link TokenAuthenticationFilter} (SR-02 must not
 * fork). Structural twin of that filter: on success it sets {@code ROLE_WEBHOOK} and nothing else
 * (SR-16's one-role-per-path rule) — it never rejects the request itself; {@code SecurityConfig}'s
 * {@code .hasRole("WEBHOOK")} rule (enforced downstream, ahead of the surviving
 * {@code .anyRequest().denyAll()}) is what actually produces 401/403 for a missing/wrong token, so a
 * filter-ordering or registration bug fails <b>closed</b>, never open (WHT-23).
 *
 * <p>Only registered by {@code SecurityConfig} at all when {@code gateway.webhook.enabled=true} — with
 * the kill-switch off, this class is never instantiated and the endpoint does not exist (WHT-24).
 */
public class GitLabWebhookSecretFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookSecretFilter.class);

    private static final String TOKEN_HEADER = "X-Gitlab-Token";

    private final GatewayProperties properties;

    public GitLabWebhookSecretFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String webhookPath = properties.getWebhook().getPath();
        if (!request.getRequestURI().equals(webhookPath)) {
            filterChain.doFilter(request, response);
            return;
        }
        String presented = request.getHeader(TOKEN_HEADER);
        if (TokenMatcher.matchesAny(presented, properties.getWebhook().getSecretTokens())) {
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    "WEBHOOK", null, List.of(new SimpleGrantedAuthority("ROLE_WEBHOOK")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } else {
            // WHT-01: never logs the presented header value itself.
            log.warn("GitLab webhook request rejected: missing/unrecognized {} for {} {}",
                    TOKEN_HEADER, request.getMethod(), request.getRequestURI());
        }
        filterChain.doFilter(request, response);
    }
}

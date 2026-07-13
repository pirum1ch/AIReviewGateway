package com.review.gateway.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Resolves a {@code Authorization: Bearer <token>} header to one of the three static roles
 * (architecture §7, SR-01/SR-02/SR-16). Does not reject the request itself on a missing/unknown
 * token — it simply leaves the {@code SecurityContext} unauthenticated, and
 * {@code SecurityConfig}'s {@code authorizeHttpRequests} rules (enforced downstream) are what
 * actually produce 401 (no/garbage token) or 403 (wrong role) for a protected path; {@code /health}
 * stays reachable with no token at all.
 *
 * <p>SR-02: token comparison is constant-time — both sides are first SHA-256-hashed to a fixed-length
 * digest, then compared with {@link MessageDigest#isEqual(byte[], byte[])} (guaranteed by the JDK to
 * take time independent of where a mismatch occurs). Never {@code String.equals}/{@code ==} on raw
 * token values.
 *
 * <p>SR-03 (SHOULD, token rotation via a configurable set of valid tokens per role) is not
 * implemented: the architecture's config surface (§9) defines exactly one token per role, and this
 * feature's scope is the three single static tokens as specified; tracked as a future enhancement,
 * consistent with the threat model's SHOULD/ACCEPTED-RISK framing for this item at the project's
 * current scale.
 */
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final GatewayProperties properties;

    public TokenAuthenticationFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            String role = matchRole(token);
            if (role != null) {
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        role, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                // SR-18: log the auth failure (path/method only, never the token value) for forensics.
                log.warn("Authentication rejected: unrecognized bearer token for {} {}", request.getMethod(), request.getRequestURI());
            }
        }
        filterChain.doFilter(request, response);
    }

    private String matchRole(String presentedToken) {
        if (constantTimeEquals(presentedToken, properties.getSecurity().getCiToken())) {
            return "CI";
        }
        if (constantTimeEquals(presentedToken, properties.getSecurity().getWorkerToken())) {
            return "WORKER";
        }
        if (constantTimeEquals(presentedToken, properties.getSecurity().getAdminToken())) {
            return "ADMIN";
        }
        return null;
    }

    private boolean constantTimeEquals(String presented, String configured) {
        if (configured == null || configured.isBlank() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(presented), sha256(configured));
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm (JCA standard names); this can never actually happen.
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
        }
    }
}

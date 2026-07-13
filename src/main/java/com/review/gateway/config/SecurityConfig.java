package com.review.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.gateway.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Stateless bearer-token authorization (architecture §7, SR-16): CSRF disabled (no cookies/sessions,
 * pure API), no session created, exactly one required role per protected path (no "CI or ADMIN"
 * ambiguity — SR-16 supersedes the looser table in architecture §7 per the threat-model's explicit
 * MUST). Unauthenticated → 401; wrong role → 403; neither body ever contains more than a short,
 * fixed machine-readable code (SR-17).
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(GatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health").permitAll()
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/reviews/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/backends", "/backends/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/metrics", "/metrics/**").hasRole("ADMIN")
                        .requestMatchers("/reviews/**").hasRole("CI")
                        .requestMatchers("/jobs/**").hasRole("WORKER")
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::handleUnauthenticated)
                        .accessDeniedHandler(this::handleForbidden))
                .addFilterBefore(new TokenAuthenticationFilter(properties), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void handleUnauthenticated(HttpServletRequest request, HttpServletResponse response, Exception ex) throws IOException {
        log.warn("Unauthenticated request rejected: {} {}", request.getMethod(), request.getRequestURI());
        writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
    }

    private void handleForbidden(HttpServletRequest request, HttpServletResponse response, Exception ex) throws IOException {
        log.warn("Forbidden request rejected: {} {} (authenticated as {})",
                request.getMethod(), request.getRequestURI(), request.getUserPrincipal());
        writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String error, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse(error, message)));
    }
}

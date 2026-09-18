package com.review.gateway.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RequestBodySizeLimitFilter} for the whole servlet container, ahead of Spring
 * Security's filter chain (SR-11: reject an oversized body before any other processing, including
 * authentication, so a flood of huge unauthenticated requests still gets a fast, cheap rejection).
 *
 * <p><b>WHR-08 (QA finding, GitLab Webhook Diff Trigger):</b> {@link RequestBodySizeLimitFilter} itself
 * has always known how to cap {@code gateway.webhook.path} (see its own {@code webhookPattern} field),
 * but this bean's {@code addUrlPatterns} — the servlet container's own URL mapping, evaluated before the
 * filter's Java code ever runs — never included it, so the filter was never actually invoked for that
 * path in the real running application; only direct unit tests calling {@code doFilterInternal} in
 * isolation exercised that branch. Registered unconditionally (harmless when
 * {@code gateway.webhook.enabled=false}: the filter's own {@code webhookPattern} is {@code null} then,
 * so it no-ops, and no controller exists to serve the path anyway — WHT-24).
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<RequestBodySizeLimitFilter> requestBodySizeLimitFilter(GatewayProperties properties) {
        FilterRegistrationBean<RequestBodySizeLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestBodySizeLimitFilter(properties));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/reviews", "/jobs/*", properties.getWebhook().getPath());
        return registration;
    }
}

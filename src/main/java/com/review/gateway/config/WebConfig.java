package com.review.gateway.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link RequestBodySizeLimitFilter} for the whole servlet container, ahead of Spring
 * Security's filter chain (SR-11: reject an oversized body before any other processing, including
 * authentication, so a flood of huge unauthenticated requests still gets a fast, cheap rejection).
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<RequestBodySizeLimitFilter> requestBodySizeLimitFilter(GatewayProperties properties) {
        FilterRegistrationBean<RequestBodySizeLimitFilter> registration =
                new FilterRegistrationBean<>(new RequestBodySizeLimitFilter(properties));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        // BSQ-11 (Backend Self-Registration): registered unconditionally, regardless of
        // gateway.backend.self-registration.enabled -- a cap on a path that 403s/404s when the feature is
        // off costs nothing, and the filter would otherwise never be invoked for these paths at all (the
        // servlet container only calls a filter for the URL patterns it is registered against here --
        // adding a PathPattern inside the filter class alone does NOT do this).
        registration.addUrlPatterns("/reviews", "/jobs/*", "/backends", "/backends/announce");
        return registration;
    }
}

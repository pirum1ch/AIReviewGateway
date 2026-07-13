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
        registration.addUrlPatterns("/reviews", "/jobs/*");
        return registration;
    }
}

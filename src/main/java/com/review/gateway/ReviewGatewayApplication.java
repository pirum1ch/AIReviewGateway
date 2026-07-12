package com.review.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Review Gateway service.
 *
 * <p>The Gateway is the sole owner of Review business logic and state; PostgreSQL
 * (via Flyway-managed schema) is the single source of truth. See
 * {@code docs/implementation-architecture.md} for the full design.
 */
@SpringBootApplication
public class ReviewGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReviewGatewayApplication.class, args);
    }
}

package com.review.gateway.controller;

import com.review.gateway.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public liveness endpoint (architecture §2/§7) — {@code permitAll}, distinct from
 * {@code /actuator/health} (which reports DB connectivity via Spring Boot Actuator).
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return HealthResponse.up();
    }
}

package com.review.gateway;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-application boot smoke test (feature/03-api-security exit criterion: "service deployable as a
 * systemd unit; all endpoints enforce roles" starts with "the whole context actually starts"). Loads
 * every bean — controllers, {@code SecurityConfig}, {@code RestClientConfig}, {@code ScheduledJobs},
 * Flyway/JPA against a real (Zonky) Postgres — and confirms the public {@code /health} endpoint
 * answers with no token required.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
class ApplicationSmokeTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointRespondsUpWithoutAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void reviewsEndpointRejectsUnauthenticatedRequests() {
        ResponseEntity<String> response = restTemplate.getForEntity("/reviews/1", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void actuatorExposesOnlyHealthNotEnvOrOtherInternals() {
        // SR-17: management.endpoints.web.exposure.include is "health" only.
        ResponseEntity<String> envResponse = restTemplate.getForEntity("/actuator/env", String.class);
        assertThat(envResponse.getStatusCode().value()).isIn(404, 401, 403);

        ResponseEntity<String> healthResponse = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(healthResponse.getStatusCode().value()).isEqualTo(200);
    }
}

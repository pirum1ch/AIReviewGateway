package com.review.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@code V1__initial_schema.sql} applies cleanly against a real (Zonky-provisioned)
 * PostgreSQL instance, and that all 7 tables from the architecture doc exist afterward. Note:
 * because {@code spring.jpa.hibernate.ddl-auto=validate} is set, simply loading the Spring context
 * for any test in this module already proves Hibernate's entity mappings match this migrated
 * schema exactly — a mismatch would fail context startup for every test, not just this one.
 */
class FlywayMigrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void allExpectedTablesExistAfterMigration() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
                String.class);

        assertThat(tables).contains(
                "reviews",
                "review_inputs",
                "review_jobs",
                "review_results",
                "review_comments",
                "review_events",
                "backends",
                "flyway_schema_history");
    }
}

package com.review.gateway;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;

/**
 * Base class for repository/entity slice tests. Boots a real, disposable PostgreSQL instance via
 * Zonky's native-binary ("ZONKY") provider — no Docker required, which matches the build host
 * constraints in {@code CLAUDE.md}. Flyway applies {@code V1__initial_schema.sql} against it, so
 * these tests exercise the real schema (partial unique indexes, CHECK constraints, SKIP LOCKED),
 * not an in-memory approximation.
 */
@DataJpaTest
@AutoConfigureEmbeddedDatabase(provider = ZONKY, type = POSTGRES)
public abstract class AbstractPostgresIntegrationTest {
}

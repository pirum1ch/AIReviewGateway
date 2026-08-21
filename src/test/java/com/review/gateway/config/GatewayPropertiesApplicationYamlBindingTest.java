package com.review.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-added (Worker Observability &amp; Claim Latency, WOC-16). Deliberately does <b>not</b> use
 * {@code @SpringBootTest} + {@code @Autowired GatewayProperties}: this module's Maven test classpath
 * puts {@code target/test-classes} ahead of {@code target/classes}, and Spring Boot's
 * {@code classpath:/application.yml} config-data location resolves to a <em>single</em> classpath
 * resource (the first one the classloader finds) rather than merging every {@code application.yml} on
 * the classpath -- so a full {@code @SpringBootTest} in this repo never actually loads {@code
 * src/main/resources/application.yml} at all; every property not present in {@code
 * src/test/resources/application.yml} silently falls back to the Java-level {@code
 * @ConfigurationProperties} field default. That is a real, separate testing-infrastructure gap (nothing
 * in the existing suite can ever catch drift between {@code src/main/resources/application.yml} and its
 * own documented/intended values), and it is exactly why the regression below shipped unnoticed: a
 * {@code @SpringBootTest} assertion on {@code properties.getBackend().getReadTimeout()} reports {@code
 * PT10S} in this test suite regardless of what {@code src/main/resources/application.yml} actually says,
 * because that file is never on the effective config-data path during tests.
 *
 * <p>This test instead loads and binds {@code src/main/resources/application.yml} directly, by path,
 * exactly the way it will be read from the shipped production JAR (where there is no competing {@code
 * test-classes} entry ahead of it on the classpath) -- the only way to actually verify what a real
 * deployment's config file resolves to.
 *
 * <p><b>Finding: this currently FAILS.</b> {@code gateway.backend.read-timeout} is still hard-coded to
 * {@code 5s} in {@code src/main/resources/application.yml} (line ~79), even though {@code
 * GatewayProperties.Backend.readTimeout}'s Java-level default was correctly raised to {@code 10s}
 * (WOC-16, "safe only because WOC-14 removes the DB-connection-held-during-probe hazard"), and {@code
 * README.md} §4.2 was updated to document the shipped default as {@code 10s} ("read-timeout was raised
 * from 5s"). Because an explicit YAML value always wins over a {@code @ConfigurationProperties} field
 * default, every real deployment booting off the shipped config file is still probing at the pre-WOC-16
 * 5s timeout — the documented rationale is true in code but was never actually applied to the config
 * that ships.
 */
class GatewayPropertiesApplicationYamlBindingTest {

    private static final Path APPLICATION_YAML = Path.of("src/main/resources/application.yml");

    private GatewayProperties.Backend bindShippedBackendConfig() throws Exception {
        assertThat(Files.exists(APPLICATION_YAML))
                .as("expected to find %s relative to the Maven module working directory", APPLICATION_YAML)
                .isTrue();

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> loaded = loader.load("application", new FileSystemResource(APPLICATION_YAML.toFile()));

        MutablePropertySources mutableSources = new MutablePropertySources();
        loaded.forEach(mutableSources::addLast);

        GatewayProperties properties = new GatewayProperties();
        Binder binder = new Binder(ConfigurationPropertySources.from(mutableSources));
        binder.bind("gateway.backend", Bindable.ofInstance(properties.getBackend()));
        return properties.getBackend();
    }

    @Test
    void shippedApplicationYamlBindsBackendReadTimeoutToTheDocumentedWoc16Default() throws Exception {
        GatewayProperties.Backend backend = bindShippedBackendConfig();

        // README.md §4.2 ("gateway.backend.connect-timeout / read-timeout | 3s / 10s ... read-timeout was
        // raised from 5s") and GatewayProperties.Backend's own javadoc both assert the shipped default is
        // 10s. If this assertion fails, application.yml's literal `read-timeout: 5s` is silently
        // overriding the Java-level default and the documentation describes behavior the shipped config
        // file does not actually produce.
        assertThat(backend.getReadTimeout())
                .as("gateway.backend.read-timeout as bound from the actual shipped src/main/resources/"
                        + "application.yml -- WOC-16/README.md §4.2 document this as 10s")
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void shippedApplicationYamlBindsBackendConnectTimeoutUnchangedAt3s() throws Exception {
        // Sanity companion: connect-timeout was NOT supposed to change (WOC-16 only raises read-timeout).
        GatewayProperties.Backend backend = bindShippedBackendConfig();
        assertThat(backend.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
    }
}

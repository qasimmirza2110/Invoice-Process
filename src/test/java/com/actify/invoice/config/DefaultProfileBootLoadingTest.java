package com.actify.invoice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves with Spring Boot's REAL config loading (not hand-merged properties) what a plain Eclipse start sees.
 * The context is empty on purpose: no SchemaSafetyGuard, no DataSource, no web server - nothing connects anywhere.
 */
class DefaultProfileBootLoadingTest {

    @Configuration
    static class Empty {
    }

    private static ConfigurableApplicationContext start(String... args) {
        return new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(args);
    }

    @Test
    void withoutAProfileBootLoadsTheLocalDemoFile() {
        try (ConfigurableApplicationContext context = start()) {
            Environment env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).isEmpty();
            assertThat(env.getDefaultProfiles()).containsExactly(SchemaSafetyGuard.LOCAL_DEMO_PROFILE);
            assertThat(SchemaSafetyGuard.effectiveProfiles(env)).containsExactly(SchemaSafetyGuard.LOCAL_DEMO_PROFILE);
            assertThat(env.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://localhost:5432/" + SchemaSafetyGuard.LOCAL_DEMO_DATABASE);
            assertThat(env.getProperty("invoice.jwt.allow-ephemeral-secret")).isEqualTo("true");
            assertThat(env.getProperty("invoice.worker.enabled")).isEqualTo("false");
            assertThat(env.getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(env.getProperty("server.port")).isEqualTo("8088");
        }
    }

    /** The container profile: external binding, Secure cookie, worker off - and still guarded by SchemaSafetyGuard. */
    @Test
    void theRenderProfileBindsToPortAndAllInterfacesAndStaysGuarded() {
        String url = "--INVOICE_DB_URL=jdbc:postgresql://db-internal.example:5432/invoice_process_prod";
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=render", "--PORT=10000", url,
                "--INVOICE_STORAGE_DIR=/var/data/invoice-documents")) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("server.address")).isEqualTo("0.0.0.0");
            assertThat(env.getProperty("server.port")).isEqualTo("10000");
            assertThat(env.getProperty("invoice.auth.cookie-secure")).isEqualTo("true");
            assertThat(env.getProperty("invoice.jwt.allow-ephemeral-secret")).isEqualTo("false");
            assertThat(env.getProperty("invoice.worker.enabled")).isEqualTo("false");
            assertThat(env.getProperty("invoice.legacy-extract.enabled")).isEqualTo("false");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(env.getProperty("invoice.storage.dir")).isEqualTo("/var/data/invoice-documents");
            // Not approved -> refused before any connection.
            assertThatThrownBy(() -> SchemaSafetyGuard.check(env)).hasMessageContaining("not approved");
        }
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=render", url,
                "--INVOICE_STORAGE_DIR=/var/data/invoice-documents", "--INVOICE_LIVE_START_APPROVED=true",
                "--INVOICE_APPROVED_DATABASE=db-internal.example:5432/invoice_process_prod")) {
            SchemaSafetyGuard.check(context.getEnvironment()); // the one approved database: allowed
        }
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=render", url,
                "--INVOICE_STORAGE_DIR=/var/data/invoice-documents", "--INVOICE_LIVE_START_APPROVED=true",
                "--INVOICE_APPROVED_DATABASE=localhost:5432/" + SchemaSafetyGuard.LIVE_DATABASE)) {
            Environment env = context.getEnvironment();
            assertThatThrownBy(() -> SchemaSafetyGuard.check(env)).hasMessageContaining("only")
                    .hasMessageContaining("is approved");
        }
    }

    /** Without INVOICE_DB_URL / INVOICE_STORAGE_DIR the render profile has no fallback to localhost or a home folder. */
    @Test
    void theRenderProfileHasNoLocalDatabaseOrStorageFallback() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=render")) {
            Environment env = context.getEnvironment();
            assertThatThrownBy(() -> env.getProperty("spring.datasource.url")).hasMessageContaining("INVOICE_DB_URL");
            assertThatThrownBy(() -> env.getProperty("invoice.storage.dir")).hasMessageContaining("INVOICE_STORAGE_DIR");
        }
    }

    @Test
    void anExplicitProfileReplacesTheLocalDemoDefault() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=live")) {
            Environment env = context.getEnvironment();
            assertThat(SchemaSafetyGuard.effectiveProfiles(env)).containsExactly("live");
            assertThat(env.getProperty("spring.datasource.url")).doesNotContain(SchemaSafetyGuard.LOCAL_DEMO_DATABASE);
            assertThat(env.getProperty("invoice.jwt.allow-ephemeral-secret")).isNull();
        }
    }
}

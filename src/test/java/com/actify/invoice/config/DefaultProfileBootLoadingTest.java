package com.actify.invoice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.Map;

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

    /**
     * Like start(), but with these OS environment variables INSTEAD of the machine's. Only a real system-environment
     * source gets Spring's relaxed binding (GEMINI_API_KEY -> gemini.api-key), which is how Render passes settings.
     */
    private static ConfigurableApplicationContext startWithEnvironment(Map<String, Object> variables, String... args) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return new SpringApplicationBuilder(Empty.class).environment(environment).web(WebApplicationType.NONE)
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

    /** The container profile: all interfaces, remote PDF storage, small pool, worker off, SchemaSafetyGuard on. */
    @Test
    void theRenderProfileBindsToPortAndAllInterfacesAndStaysGuarded() {
        String url = "--INVOICE_DB_URL=jdbc:postgresql://db-internal.example:5432/invoice_process_prod";
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=render", "--PORT=10000", url,
                "--INVOICE_S3_BUCKET=invoice-documents")) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("server.address")).isEqualTo("0.0.0.0");
            assertThat(env.getProperty("server.port")).isEqualTo("10000");
            assertThat(env.getProperty("invoice.cors.allowed-origins")).isEmpty();
            assertThat(env.getProperty("invoice.jwt.allow-ephemeral-secret")).isEqualTo("false");
            assertThat(env.getProperty("invoice.worker.enabled")).isEqualTo("false");
            assertThat(env.getProperty("invoice.legacy-extract.enabled")).isEqualTo("false");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(env.getProperty("invoice.storage.type")).isEqualTo("s3");
            assertThat(env.getProperty("invoice.storage.s3.bucket")).isEqualTo("invoice-documents");
            assertThat(env.getProperty("invoice.storage.require-remote")).isEqualTo("true");
            assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("3");
            assertThat(env.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("0");
            assertThat(env.getProperty("invoice.storage.verify-on-startup")).isEqualTo("true");
            assertThat(env.getProperty("invoice.gemini.require-paid-tier")).isEqualTo("true");
            assertThat(env.getProperty("invoice.gemini.paid-tier-confirmed")).isEqualTo("false");
            // Not approved -> refused before any connection.
            assertThatThrownBy(() -> SchemaSafetyGuard.check(env)).hasMessageContaining("not approved");
        }
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=render", url,
                "--INVOICE_LIVE_START_APPROVED=true",
                "--INVOICE_APPROVED_DATABASE=db-internal.example:5432/invoice_process_prod")) {
            SchemaSafetyGuard.check(context.getEnvironment()); // the one approved database: allowed
        }
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=render", url,
                "--INVOICE_LIVE_START_APPROVED=true",
                "--INVOICE_APPROVED_DATABASE=localhost:5432/" + SchemaSafetyGuard.LIVE_DATABASE)) {
            Environment env = context.getEnvironment();
            assertThatThrownBy(() -> SchemaSafetyGuard.check(env)).hasMessageContaining("only")
                    .hasMessageContaining("is approved");
        }
    }

    /** Without INVOICE_DB_URL the render profile has no localhost fallback, and PDFs never fall back to a local folder. */
    @Test
    void theRenderProfileHasNoLocalDatabaseOrStorageFallback() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=render")) {
            Environment env = context.getEnvironment();
            assertThatThrownBy(() -> env.getProperty("spring.datasource.url")).hasMessageContaining("INVOICE_DB_URL");
            assertThat(env.getProperty("invoice.storage.type")).isEqualTo("s3");
            assertThat(env.getProperty("invoice.storage.s3.bucket")).isEmpty(); // -> S3DocumentStorage refuses to start
        }
    }

    /**
     * Render-style OS environment variables switch the owner decisions on through relaxed binding; nothing else does.
     * The local demo keeps the paid-project requirement off (synthetic files only); every other start has it on.
     */
    @Test
    void ownerDecisionsComeOnlyFromExplicitEnvironmentVariables() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("SPRING_PROFILES_ACTIVE", "render");
        variables.put("INVOICE_DB_URL", "jdbc:postgresql://db-internal.example:5432/invoice_process_prod");
        variables.put("INVOICE_S3_ENDPOINT", "https://storage.example.com");
        variables.put("INVOICE_GEMINI_PAID_TIER_CONFIRMED", "true");
        variables.put("INVOICE_S3_BUCKET", "invoice-documents");
        variables.put("INVOICE_CORS_ALLOWED_ORIGINS", "https://invoices.example.com");
        try (ConfigurableApplicationContext context = startWithEnvironment(variables)) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("invoice.gemini.paid-tier-confirmed")).isEqualTo("true");
            assertThat(env.getProperty("invoice.storage.s3.endpoint")).isEqualTo("https://storage.example.com");
            assertThat(env.getProperty("invoice.storage.s3.bucket")).isEqualTo("invoice-documents");
            assertThat(env.getProperty("invoice.cors.allowed-origins")).isEqualTo("https://invoices.example.com");
        }
        try (ConfigurableApplicationContext context = start()) {
            assertThat(context.getEnvironment().getProperty("invoice.gemini.require-paid-tier")).isEqualTo("false");
        }
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=live")) {
            assertThat(context.getEnvironment().getProperty("invoice.gemini.require-paid-tier")).isEqualTo("true");
        }
    }

    /** The temporary free-demo profile was removed: naming it loads no profile file, so nothing demo-specific applies. */
    @Test
    void theRemovedFreeDemoProfileLoadsNothingSpecial() {
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=render-free-demo")) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("invoice.free-demo.profile-file-loaded")).isNull();
            assertThat(env.getProperty("invoice.free-demo.allowed-upload-sha256")).isNull();
            // Without the render profile it would still bind to 127.0.0.1 only, so such a deploy cannot go live.
            assertThat(env.getProperty("server.address")).isEqualTo("127.0.0.1");
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

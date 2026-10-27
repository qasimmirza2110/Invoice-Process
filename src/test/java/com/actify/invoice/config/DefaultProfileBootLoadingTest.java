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
            assertThat(env.getProperty("invoice.storage.verify-on-startup")).isEqualTo("true");
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

    /**
     * The free demo with Spring Boot's real loading: its own file, ephemeral storage, no Gemini - and it is still
     * guarded by SchemaSafetyGuard. The second start passes Render-style OS environment variables, which override
     * the profile file through relaxed binding; the guard must catch every one of them.
     */
    @Test
    void theFreeDemoProfileIsSeparateGuardedAndRefusesOverrides() {
        String url = "--INVOICE_DB_URL=jdbc:postgresql://dpg-demo.example:5432/invoice_demo";
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=render-free-demo", url)) {
            Environment env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).containsExactly(RenderFreeDemoGuard.PROFILE);
            assertThat(env.getProperty("invoice.storage.dir")).isEqualTo(RenderFreeDemoGuard.STORAGE_ROOT);
            assertThat(env.getProperty("server.address")).isEqualTo("0.0.0.0");
            assertThat(env.getProperty("invoice.auth.cookie-secure")).isEqualTo("true");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(env.getProperty("gemini.api-key")).isEmpty();
            RenderFreeDemoGuard.check(env);                                       // passes as shipped
            assertThatThrownBy(() -> SchemaSafetyGuard.check(env)).hasMessageContaining("not approved");
        }
        Map<String, Object> render = new HashMap<>();
        render.put("SPRING_PROFILES_ACTIVE", RenderFreeDemoGuard.PROFILE);
        render.put("INVOICE_DB_URL", "jdbc:postgresql://dpg-demo.example:5432/invoice_demo");
        render.put("GEMINI_API_KEY", "test-only-value");
        render.put("INVOICE_WORKER_ENABLED", "true");
        render.put("INVOICE_STORAGE_DIR", "/var/data/invoice-documents");
        try (ConfigurableApplicationContext context = startWithEnvironment(render)) {
            Environment env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).containsExactly(RenderFreeDemoGuard.PROFILE);
            assertThat(env.getProperty("gemini.api-key")).isEqualTo("test-only-value");   // the variable won
            assertThatThrownBy(() -> RenderFreeDemoGuard.check(env))
                    .hasMessageContaining("Gemini API key is set")
                    .hasMessageContaining("invoice.worker.enabled must be false")
                    .hasMessageContaining("invoice.storage.dir must be")
                    .hasMessageNotContaining("test-only-value");
        }
        // The production profile is unchanged by the demo: still the disk folder, no demo marker.
        try (ConfigurableApplicationContext context = start("--spring.profiles.active=render", url,
                "--INVOICE_STORAGE_DIR=/var/data/invoice-documents")) {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("invoice.free-demo.profile-file-loaded")).isNull();
            assertThat(RenderFreeDemoGuard.isActive(env)).isFalse();
            RenderFreeDemoGuard.check(env);
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

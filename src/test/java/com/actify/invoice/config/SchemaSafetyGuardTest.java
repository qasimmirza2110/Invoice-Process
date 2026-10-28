package com.actify.invoice.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves the startup guard runs BEFORE any database bean exists, without using a database.
 *
 * Each context below has a DataSource @Bean whose factory method flips a flag. If the guard refuses the start,
 * the flag must still be false: the DataSource (and therefore Hibernate and its DDL) was never created.
 */
class SchemaSafetyGuardTest {

    private static final String LIVE_URL = "jdbc:postgresql://localhost:5432/Actify-Internship-Invoice-Process_db";
    private static final String LIVE_PIN = "localhost:5432/Actify-Internship-Invoice-Process_db";
    private static final String TEST_URL = "jdbc:postgresql://localhost:5432/" + SchemaSafetyGuard.TEST_DATABASE;

    static final AtomicBoolean DATA_SOURCE_CREATED = new AtomicBoolean();

    @Configuration
    static class ProbeDataSourceConfig {
        @Bean
        DataSource dataSource() {
            DATA_SOURCE_CREATED.set(true);
            return mock(DataSource.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchemaSafetyGuard.class, ProbeDataSourceConfig.class);

    @BeforeEach
    void reset() {
        DATA_SOURCE_CREATED.set(false);
    }

    @Test
    void liveUrlWithUpdateIsRefusedBeforeTheDataSourceIsCreated() {
        runner.withPropertyValues("spring.datasource.url=" + LIVE_URL, "spring.jpa.hibernate.ddl-auto=update")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("SchemaSafetyGuard: REFUSING to start");
                    assertThat(DATA_SOURCE_CREATED).isFalse();
                });
    }

    @Test
    void liveUrlWithValidateButWithoutApprovalIsStillRefused() {
        runner.withPropertyValues("spring.datasource.url=" + LIVE_URL, "spring.jpa.hibernate.ddl-auto=validate")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("not approved");
                    assertThat(DATA_SOURCE_CREATED).isFalse();
                });
    }

    @Test
    void approvedLiveStartIsStillRefusedIfAnySchemaChangingSettingIsOn() {
        String[][] unsafe = {
                {"spring.jpa.hibernate.ddl-auto", "update"},
                {"spring.jpa.hibernate.ddl-auto", "create-drop"},
                {"spring.jpa.properties.hibernate.hbm2ddl.auto", "update"},
                {"spring.jpa.properties.javax.persistence.schema-generation.database.action", "create"},
                {"spring.jpa.properties.jakarta.persistence.schema-generation.database.action", "create"},
                {"hibernate.hbm2ddl.auto", "update"},
                {"javax.persistence.schema-generation.database.action", "drop-and-create"},
                {"spring.jpa.generate-ddl", "true"},
                {"spring.sql.init.mode", "always"}};
        for (String[] setting : unsafe) {
            DATA_SOURCE_CREATED.set(false);
            runner.withPropertyValues("spring.datasource.url=" + LIVE_URL, "invoice.live-start-approved=true",
                            "invoice.approved-database=" + LIVE_PIN, "spring.jpa.hibernate.ddl-auto=validate",
                            setting[0] + "=" + setting[1])
                    .run(context -> {
                        assertThat(context).as(setting[0]).hasFailed();
                        assertThat(context.getStartupFailure()).hasStackTraceContaining(setting[0]);
                        assertThat(DATA_SOURCE_CREATED).as(setting[0]).isFalse();
                    });
        }
    }

    @Test
    void approvedStartForThePinnedDatabaseWithValidateIsAllowed() {
        runner.withPropertyValues("spring.datasource.url=" + LIVE_URL, "invoice.live-start-approved=true",
                        "invoice.approved-database=" + LIVE_PIN, "spring.jpa.hibernate.ddl-auto=validate")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(DATA_SOURCE_CREATED).isTrue();
                });
    }

    @Test
    void pgtestProfileWithTheExactTestDatabaseMayUseUpdate() {
        runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("pgtest"))
                .withPropertyValues("spring.datasource.url=" + TEST_URL, "spring.jpa.hibernate.ddl-auto=update")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(DATA_SOURCE_CREATED).isTrue();
                });
    }

    @Test
    void pgtestProfileWithAnyOtherDatabaseIsRefused() {
        runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("pgtest"))
                .withPropertyValues("spring.datasource.url=" + LIVE_URL, "spring.jpa.hibernate.ddl-auto=update")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(DATA_SOURCE_CREATED).isFalse();
                });
    }

    @Test
    void testDatabaseUrlWithoutTheExactProfileIsRefused() {
        runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("pgtest", "extra"))
                .withPropertyValues("spring.datasource.url=" + TEST_URL, "spring.jpa.hibernate.ddl-auto=update")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(DATA_SOURCE_CREATED).isFalse();
                });
    }

    /** S1: the approval flag alone is not enough - it must name the exact database of the JDBC URL. */
    @Test
    void approvalWithoutAPinOrForAnotherDatabaseIsRefusedBeforeTheDataSourceExists() {
        String[][] cases = {
                {"", "is not set"},
                {"localhost:5432/Some-Scratch-Copy_db", "only localhost:5432/some"},
                {"localhost:5433/Actify-Internship-Invoice-Process_db", "only localhost:5433/"},
                {"db.example.test:5432/Actify-Internship-Invoice-Process_db", "only db.example.test:5432/"},
                {"not-an-endpoint", "must look like host:port/database"}};
        for (String[] c : cases) {
            DATA_SOURCE_CREATED.set(false);
            runner.withPropertyValues("spring.datasource.url=" + LIVE_URL, "invoice.live-start-approved=true",
                            "invoice.approved-database=" + c[0], "spring.jpa.hibernate.ddl-auto=validate")
                    .run(context -> {
                        assertThat(context).as(c[0]).hasFailed();
                        assertThat(context.getStartupFailure().getMessage() + rootMessage(context.getStartupFailure()))
                                .as(c[0]).containsIgnoringCase(c[1]);
                        assertThat(DATA_SOURCE_CREATED).as(c[0]).isFalse();
                    });
        }
    }

    /** S2: Hibernate reads bare -D system properties too; the guard sees them through the Spring Environment. */
    @Test
    void bareJvmSystemPropertiesThatChangeTheSchemaAreRefused() {
        for (String property : new String[]{"hibernate.hbm2ddl.auto=update",
                "javax.persistence.schema-generation.database.action=create"}) {
            DATA_SOURCE_CREATED.set(false);
            runner.withSystemProperties(property)
                    .withPropertyValues("spring.datasource.url=" + LIVE_URL, "invoice.live-start-approved=true",
                            "invoice.approved-database=" + LIVE_PIN, "spring.jpa.hibernate.ddl-auto=validate")
                    .run(context -> {
                        assertThat(context).as(property).hasFailed();
                        assertThat(DATA_SOURCE_CREATED).as(property).isFalse();
                    });
        }
    }

    @Test
    void endpointParsingNormalizesAndRefusesAmbiguousUrls() {
        assertThat(SchemaSafetyGuard.endpointOf("jdbc:postgresql://LocalHost/db_a?user=x&password=secret"))
                .isEqualTo("localhost:5432/db_a");
        assertThat(SchemaSafetyGuard.endpointOf("jdbc:postgresql://127.0.0.1:5433/db_b")).isEqualTo("127.0.0.1:5433/db_b");
        assertThat(SchemaSafetyGuard.endpointOf("jdbc:postgresql://h1,h2/db")).isNull();      // failover list
        assertThat(SchemaSafetyGuard.endpointOf("jdbc:postgresql://u:p@h/db")).isNull();      // credentials in host
        assertThat(SchemaSafetyGuard.endpointOf("jdbc:postgresql://h/")).isNull();
        assertThat(SchemaSafetyGuard.endpointOf("jdbc:mysql://h/db")).isNull();
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return String.valueOf(root.getMessage());
    }

    // ---------------------------------------------------------------- relaxed spellings and the deprecated init key

    @Test
    void relaxedSpellingsAndTheDeprecatedInitKeyAreRefusedToo() {
        for (String setting : new String[]{"spring.jpa.hibernate.ddlAuto=update", "spring.jpa.hibernate.ddl_auto=create",
                "spring.datasource.initialization-mode=always", "spring.jpa.properties[hibernate.hbm2ddl.auto]=update"}) {
            DATA_SOURCE_CREATED.set(false);
            runner.withPropertyValues("spring.datasource.url=" + LIVE_URL, "invoice.live-start-approved=true",
                            "invoice.approved-database=" + LIVE_PIN, setting)
                    .run(context -> {
                        assertThat(context).as(setting).hasFailed();
                        assertThat(DATA_SOURCE_CREATED).as(setting).isFalse();
                    });
        }
        DATA_SOURCE_CREATED.set(false);
        runner.withInitializer(environmentVariables("SPRING_JPA_HIBERNATE_DDL_AUTO", "update"))
                .withPropertyValues("spring.datasource.url=" + LIVE_URL, "invoice.live-start-approved=true",
                        "invoice.approved-database=" + LIVE_PIN)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(DATA_SOURCE_CREATED).isFalse();
                });
    }

    @Test
    void pgtestProfileIsPinnedToLocalhost5432NotOnlyToTheDatabaseName() {
        for (String url : new String[]{"jdbc:postgresql://db.example.test:5432/" + SchemaSafetyGuard.TEST_DATABASE,
                "jdbc:postgresql://localhost:5433/" + SchemaSafetyGuard.TEST_DATABASE}) {
            DATA_SOURCE_CREATED.set(false);
            runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("pgtest"))
                    .withPropertyValues("spring.datasource.url=" + url, "spring.jpa.hibernate.ddl-auto=update")
                    .run(context -> {
                        assertThat(context).as(url).hasFailed();
                        assertThat(DATA_SOURCE_CREATED).as(url).isFalse();
                    });
        }
    }

    // ---------------------------------------------------------------- local demo profile

    /** application.properties overlaid with application-localdemo.properties, as Spring Boot would load them. */
    private ApplicationContextRunner localDemo(String... extra) throws Exception {
        Properties merged = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        merged.putAll(PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-localdemo.properties")));
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<Object, Object> e : merged.entrySet()) {
            pairs.add(e.getKey() + "=" + e.getValue());
        }
        pairs.addAll(Arrays.asList(extra));
        return runner.withInitializer(c -> c.getEnvironment().setActiveProfiles(SchemaSafetyGuard.LOCAL_DEMO_PROFILE))
                .withPropertyValues(pairs.toArray(new String[0]));
    }

    /** Simulates OS environment variables with the same name mapping Spring uses for real ones (FOO_BAR -> foo.bar). */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> environmentVariables(String... keyValues) {
        Map<String, Object> variables = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            variables.put(keyValues[i], keyValues[i + 1]);
        }
        return c -> c.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("test-environment-variables", variables));
    }

    private static void assertRefusedBeforeDataSource(AssertableApplicationContext context, String why, String expected) {
        assertThat(context).as(why).hasFailed();
        assertThat(context.getStartupFailure()).as(why).hasStackTraceContaining("SchemaSafetyGuard: REFUSING to start");
        assertThat(context.getStartupFailure().getMessage() + rootMessage(context.getStartupFailure()))
                .as(why).containsIgnoringCase(expected);
        assertThat(DATA_SOURCE_CREATED).as(why).isFalse();
    }

    @Test
    void theLocalDemoProfileAsShippedIsAllowedAndNonMutating() throws Exception {
        Properties demo = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-localdemo.properties"));
        assertThat(demo.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost:5432/" + SchemaSafetyGuard.LOCAL_DEMO_DATABASE);
        assertThat(demo.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(demo.getProperty("gemini.api-key")).isEmpty();
        assertThat(demo.getProperty("invoice.worker.enabled")).isEqualTo("false");

        localDemo().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(DATA_SOURCE_CREATED).isTrue();
        });
    }

    @Test
    void theLocalDemoNeverReachesTheLiveOrTestDatabaseEvenWithTheApprovalVariables() throws Exception {
        String[] urls = {LIVE_URL, TEST_URL, "jdbc:postgresql://db.example.test:5432/" + SchemaSafetyGuard.LIVE_DATABASE};
        for (String url : urls) {
            DATA_SOURCE_CREATED.set(false);
            localDemo().withInitializer(environmentVariables("SPRING_DATASOURCE_URL", url))
                    .run(context -> assertRefusedBeforeDataSource(context, url, "may never use"));
            DATA_SOURCE_CREATED.set(false);
            localDemo("spring.datasource.url=" + url).withInitializer(environmentVariables(
                            "INVOICE_LIVE_START_APPROVED", "true", "INVOICE_APPROVED_DATABASE", SchemaSafetyGuard.endpointOf(url)))
                    .run(context -> assertRefusedBeforeDataSource(context, url, "must not set INVOICE_LIVE_START_APPROVED"));
        }
    }

    @Test
    void theLocalDemoAcceptsOnlyTheExactLocalhostEndpoint() throws Exception {
        String demo = SchemaSafetyGuard.LOCAL_DEMO_DATABASE;
        String[] urls = {"jdbc:postgresql://127.0.0.1:5432/" + demo, "jdbc:postgresql://localhost:5433/" + demo,
                "jdbc:postgresql://db.example.test/" + demo, "jdbc:postgresql://localhost:5432/other_demo_db",
                "jdbc:postgresql://h1,localhost/" + demo, "jdbc:mysql://localhost/" + demo};
        for (String url : urls) {
            DATA_SOURCE_CREATED.set(false);
            localDemo().withInitializer(environmentVariables("SPRING_DATASOURCE_URL", url)).run(context ->
                    assertRefusedBeforeDataSource(context, url, url.contains(",") || url.contains("mysql")
                            ? "not a single-host" : "only " + SchemaSafetyGuard.LOCAL_DEMO_ENDPOINT));
        }
    }

    @Test
    void theLocalDemoRefusesTheApprovalVariablesOnTheirOwn() throws Exception {
        localDemo().withInitializer(environmentVariables("INVOICE_LIVE_START_APPROVED", "true"))
                .run(context -> assertRefusedBeforeDataSource(context, "approval flag", "must not set"));
        DATA_SOURCE_CREATED.set(false);
        localDemo().withInitializer(environmentVariables("INVOICE_APPROVED_DATABASE", SchemaSafetyGuard.LOCAL_DEMO_ENDPOINT))
                .run(context -> assertRefusedBeforeDataSource(context, "approved database", "must not set"));
    }

    @Test
    void theLocalDemoRefusesAGeminiKeyOrTheWorkerWithoutShowingTheKey() throws Exception {
        String fakeKey = "fake-key-value-that-must-never-appear";
        localDemo().withInitializer(environmentVariables("GEMINI_API_KEY", fakeKey)).run(context -> {
            assertRefusedBeforeDataSource(context, "gemini key", "remove GEMINI_API_KEY");
            assertThat(context.getStartupFailure()).hasStackTraceContaining("REFUSING");
            assertThat(stackTraceOf(context.getStartupFailure())).doesNotContain(fakeKey);
        });
        DATA_SOURCE_CREATED.set(false);
        localDemo().withInitializer(environmentVariables("INVOICE_WORKER_ENABLED", "true"))
                .run(context -> assertRefusedBeforeDataSource(context, "worker", "INVOICE_WORKER_ENABLED"));
    }

    // ---- explicit local-demo Gemini opt-in (INVOICE_LOCALDEMO_GEMINI_ENABLED) ----

    private static final String FAKE_KEY = "fake-key-value-that-must-never-appear";

    /** A complete, valid opt-in WITHOUT the worker: key, explicit model and whole-number limits. */
    private static final String[] OPT_IN = {"INVOICE_LOCALDEMO_GEMINI_ENABLED", "true", "GEMINI_API_KEY", FAKE_KEY,
            "GEMINI_MODEL", "gemini-3.5-flash-lite", "GEMINI_LIMIT_RPM", "15", "GEMINI_LIMIT_TPM", "250000",
            "GEMINI_LIMIT_RPD", "1000", "GEMINI_INITIAL_TOKEN_ESTIMATE", "3000"};

    private static void assertStarted(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        assertThat(DATA_SOURCE_CREATED).isTrue();
    }

    private static String[] without(String[] pairs, String key) {
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (!pairs[i].equals(key)) {
                kept.add(pairs[i]);
                kept.add(pairs[i + 1]);
            }
        }
        return kept.toArray(new String[0]);
    }

    @Test
    void theGeminiOptInAllowsAKeyButOnlyForTheOfficialEndpointAndWithoutShowingIt() throws Exception {
        localDemo().withInitializer(environmentVariables(OPT_IN)).run(SchemaSafetyGuardTest::assertStarted);
        DATA_SOURCE_CREATED.set(false);
        localDemo().withInitializer(environmentVariables(concat(OPT_IN, "GEMINI_BASE_URL", "https://attacker.example.test/v1beta")))
                .run(context -> {
                    assertRefusedBeforeDataSource(context, "other endpoint", "may only be sent to");
                    assertThat(stackTraceOf(context.getStartupFailure())).doesNotContain(FAKE_KEY);
                });
        DATA_SOURCE_CREATED.set(false);
        localDemo().withInitializer(environmentVariables(without(OPT_IN, "GEMINI_API_KEY")))
                .run(context -> assertRefusedBeforeDataSource(context, "opt-in without key", "GEMINI_API_KEY is not set"));
    }

    @Test
    void theGeminiOptInNeedsAnExplicitModelNotTheSilentDefault() throws Exception {
        localDemo().withInitializer(environmentVariables(without(OPT_IN, "GEMINI_MODEL")))
                .run(context -> assertRefusedBeforeDataSource(context, "no model", "set GEMINI_MODEL explicitly"));
        DATA_SOURCE_CREATED.set(false);
        localDemo().withInitializer(environmentVariables(concat(without(OPT_IN, "GEMINI_MODEL"), "GEMINI_MODEL", "Gemini Flash; rm")))
                .run(context -> assertRefusedBeforeDataSource(context, "odd model", "plain model id"));
        DATA_SOURCE_CREATED.set(false);
        // e.g. -Dgemini.model=... or --gemini.model=... : a source ABOVE the environment variables.
        localDemo().withInitializer(environmentVariables(OPT_IN))
                .withInitializer(c -> c.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                        "command-line", java.util.Collections.<String, Object>singletonMap("gemini.model", "some-other-model"))))
                .run(context -> assertRefusedBeforeDataSource(context, "model overridden elsewhere", "nothing else may override"));
    }

    @Test
    void theGeminiOptInNeedsWholeNumberLimitsAndNamesOnlyTheProperty() throws Exception {
        localDemo().withInitializer(environmentVariables(concat(without(OPT_IN, "GEMINI_LIMIT_TPM"), "GEMINI_LIMIT_TPM", "250k")))
                .run(context -> {
                    assertRefusedBeforeDataSource(context, "250k", "GEMINI_LIMIT_TPM");
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("not 250k");
                    assertThat(stackTraceOf(context.getStartupFailure())).doesNotContain(FAKE_KEY);
                });
        DATA_SOURCE_CREATED.set(false);
        localDemo().withInitializer(environmentVariables(without(OPT_IN, "GEMINI_LIMIT_RPD")))
                .run(context -> assertRefusedBeforeDataSource(context, "no RPD", "GEMINI_LIMIT_RPD"));
        DATA_SOURCE_CREATED.set(false);
        localDemo().withInitializer(environmentVariables(without(OPT_IN, "GEMINI_INITIAL_TOKEN_ESTIMATE")))
                .run(context -> assertRefusedBeforeDataSource(context, "no estimate", "GEMINI_INITIAL_TOKEN_ESTIMATE"));
    }

    @Test
    void theLocalDemoNeverOffersTheLegacyExtractionThatWouldBypassTheAllowlist() throws Exception {
        Properties demo = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-localdemo.properties"));
        assertThat(demo.getProperty(SchemaSafetyGuard.LEGACY_EXTRACT_PROPERTY)).isEqualTo("false");
        localDemo().withInitializer(environmentVariables(concat(OPT_IN, "INVOICE_LEGACY_EXTRACT_ENABLED", "true")))
                .run(context -> assertRefusedBeforeDataSource(context, "legacy on", "legacy single-file extraction must stay"));
    }

    @Test
    void theGeminiOptInNeverWidensTheDatabaseRules() throws Exception {
        localDemo().withInitializer(environmentVariables(concat(OPT_IN, "SPRING_DATASOURCE_URL", LIVE_URL)))
                .run(context -> assertRefusedBeforeDataSource(context, "opt-in on live", "may never use"));
        DATA_SOURCE_CREATED.set(false);
        localDemo().withInitializer(environmentVariables(concat(OPT_IN, "SPRING_JPA_HIBERNATE_DDL_AUTO", "update")))
                .run(context -> assertRefusedBeforeDataSource(context, "opt-in with update", "could change the database schema"));
    }

    @Test
    void aLocalDemoWorkerMustBeLimitedToNamedInvoicesSoTheBacklogIsNeverProcessed() throws Exception {
        String[] base = concat(OPT_IN, "INVOICE_WORKER_ENABLED", "true");
        localDemo().withInitializer(environmentVariables(base))
                .run(context -> assertRefusedBeforeDataSource(context, "worker without ids", "INVOICE_WORKER_ONLY_INVOICE_IDS"));
        DATA_SOURCE_CREATED.set(false);
        localDemo().withInitializer(environmentVariables(concat(base, "INVOICE_WORKER_ONLY_INVOICE_IDS", "17, x")))
                .run(context -> assertRefusedBeforeDataSource(context, "bad ids", "comma-separated list"));
        DATA_SOURCE_CREATED.set(false);
        localDemo().withInitializer(environmentVariables(concat(base, "INVOICE_WORKER_ONLY_INVOICE_IDS", "17")))
                .run(SchemaSafetyGuardTest::assertStarted);
        DATA_SOURCE_CREATED.set(false);
        // The worker switch alone (no opt-in) is still refused, exactly as before.
        localDemo().withInitializer(environmentVariables("INVOICE_WORKER_ENABLED", "true", "INVOICE_WORKER_ONLY_INVOICE_IDS", "17"))
                .run(context -> assertRefusedBeforeDataSource(context, "worker without opt-in", "INVOICE_WORKER_ENABLED"));
    }

    // ---- INVOICE_WORKER_ENABLED in every spelling Spring's binding accepts ----

    private static final String[] WORKER_ON = {"true", "TRUE", "True", " true ", "yes", "YES", "on", "On", "1"};
    private static final String[] WORKER_OFF = {"false", "FALSE", "no", "off", "0"};

    @Test
    void theSpellingsUnderTestAreExactlyTheOnesSpringBindsToABoolean() {
        // ApplicationConversionService is what Spring Boot uses for @Value("${invoice.worker.enabled:false}").
        org.springframework.boot.convert.ApplicationConversionService conversion =
                new org.springframework.boot.convert.ApplicationConversionService();
        for (String on : WORKER_ON) {
            assertThat(conversion.convert(on.trim(), Boolean.class)).as(on).isTrue();
        }
        for (String off : WORKER_OFF) {
            assertThat(conversion.convert(off, Boolean.class)).as(off).isFalse();
        }
    }

    @Test
    void theLocalDemoRefusesTheWorkerInEverySpellingThatSwitchesItOn() throws Exception {
        for (String on : WORKER_ON) {
            DATA_SOURCE_CREATED.set(false);
            localDemo().withInitializer(environmentVariables("INVOICE_WORKER_ENABLED", on))
                    .run(context -> assertRefusedBeforeDataSource(context, "worker '" + on + "'", "INVOICE_WORKER_ENABLED"));
        }
    }

    @Test
    void theLocalDemoStillStartsWithTheWorkerOffInEverySpelling() throws Exception {
        for (String off : WORKER_OFF) {
            DATA_SOURCE_CREATED.set(false);
            localDemo().withInitializer(environmentVariables("INVOICE_WORKER_ENABLED", off))
                    .run(SchemaSafetyGuardTest::assertStarted);
        }
    }

    @Test
    void anUnreadableWorkerSwitchRefusesTheLocalDemo() throws Exception {
        localDemo().withInitializer(environmentVariables("INVOICE_WORKER_ENABLED", "maybe"))
                .run(context -> assertRefusedBeforeDataSource(context, "worker 'maybe'", "must be true or false"));
    }

    @Test
    void withTheGeminiOptInAnyOnSpellingStillNeedsTheInvoiceAllowlist() throws Exception {
        for (String on : new String[]{"yes", "on", "1"}) {
            DATA_SOURCE_CREATED.set(false);
            localDemo().withInitializer(environmentVariables(concat(OPT_IN, "INVOICE_WORKER_ENABLED", on)))
                    .run(context -> assertRefusedBeforeDataSource(context, "opt-in, worker '" + on + "'",
                            "INVOICE_WORKER_ONLY_INVOICE_IDS"));
        }
        DATA_SOURCE_CREATED.set(false);
        localDemo().withInitializer(environmentVariables(concat(OPT_IN, "INVOICE_WORKER_ENABLED", "yes",
                        "INVOICE_WORKER_ONLY_INVOICE_IDS", "17")))
                .run(SchemaSafetyGuardTest::assertStarted);
    }

    private static String[] concat(String[] a, String... b) {
        String[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    @Test
    void theLocalDemoRefusesSchemaChangesFromEnvironmentVariablesJvmOptionsAndRelaxedSpellings() throws Exception {
        localDemo().withInitializer(environmentVariables("SPRING_JPA_HIBERNATE_DDL_AUTO", "update"))
                .run(context -> assertRefusedBeforeDataSource(context, "env ddl-auto", "could change the database schema"));
        DATA_SOURCE_CREATED.set(false);
        localDemo().withSystemProperties("hibernate.hbm2ddl.auto=create")
                .run(context -> assertRefusedBeforeDataSource(context, "-D hbm2ddl", "could change the database schema"));
        DATA_SOURCE_CREATED.set(false);
        localDemo("spring.jpa.hibernate.ddlAuto=update")
                .run(context -> assertRefusedBeforeDataSource(context, "ddlAuto", "could change the database schema"));
    }

    @Test
    void theLocalDemoProfileMustBeTheOnlyActiveProfile() throws Exception {
        localDemo().withInitializer(c -> c.getEnvironment().setActiveProfiles(SchemaSafetyGuard.LOCAL_DEMO_PROFILE, "pgtest"))
                .run(context -> assertRefusedBeforeDataSource(context, "two profiles", "exactly one active profile"));
    }

    private static String stackTraceOf(Throwable t) {
        java.io.StringWriter out = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }

    /** The real files as plain key/values; withDemoFile = also application-localdemo.properties on top. */
    private ApplicationContextRunner mainConfiguration(boolean withDemoFile, String... skipKeys) throws Exception {
        Properties merged = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        if (withDemoFile) {
            merged.putAll(PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-localdemo.properties")));
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<Object, Object> e : merged.entrySet()) {
            if (!Arrays.asList(skipKeys).contains(String.valueOf(e.getKey()))) {
                pairs.add(e.getKey() + "=" + e.getValue());
            }
        }
        return runner.withPropertyValues(pairs.toArray(new String[0]));
    }

    @Test
    void theMainConfigurationIsNonMutatingAndMakesTheLocalDemoTheDefaultProfile() throws Exception {
        Properties main = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        assertThat(main.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(main.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(main.getProperty("invoice.live-start-approved")).isEqualTo("${INVOICE_LIVE_START_APPROVED:false}");
        assertThat(main.getProperty("spring.profiles.default")).isEqualTo(SchemaSafetyGuard.LOCAL_DEMO_PROFILE);
        // The one-run random login key is switched on ONLY by the local-demo profile file.
        assertThat(main.getProperty("invoice.jwt.allow-ephemeral-secret")).isNull();
    }

    /** No profile but only the main file (its live URL): the local-demo rules apply and refuse the live database. */
    @Test
    void aStartWithoutProfileIsALocalDemoStartAndNeverReachesTheLiveDatabase() throws Exception {
        mainConfiguration(false).run(context ->
                assertRefusedBeforeDataSource(context, "no profile, live URL", "may never use"));
    }

    /** What a plain Eclipse "Run As > Java Application" loads: main file + local-demo file, no profile set. */
    @Test
    void aStartWithoutProfileWithTheLocalDemoFileIsAllowed() throws Exception {
        mainConfiguration(true).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(DATA_SOURCE_CREATED).isTrue();
        });
    }

    /** The old Eclipse configuration (Gemini key, no profile) now falls under the local-demo rules and is refused. */
    @Test
    void aStartWithoutProfileButWithAGeminiKeyIsRefusedWithoutShowingTheKey() throws Exception {
        mainConfiguration(true, "gemini.api-key")
                .withInitializer(environmentVariables("GEMINI_API_KEY", "not-a-real-key-123"))
                .run(context -> {
                    assertRefusedBeforeDataSource(context, "gemini key", "must not have a Gemini API key");
                    assertThat(stackTraceOf(context.getStartupFailure())).doesNotContain("not-a-real-key-123");
                });
    }

    @Test
    void anExplicitNonDemoProfileStillNeedsTheApproval() throws Exception {
        mainConfiguration(false).withInitializer(c -> c.getEnvironment().setActiveProfiles("live")).run(context ->
                assertRefusedBeforeDataSource(context, "explicit live profile", "not approved"));
    }

    @Test
    void approvalVariablesWithoutAnExplicitProfileExplainWhatIsMissing() throws Exception {
        mainConfiguration(false).withInitializer(environmentVariables(
                        "INVOICE_LIVE_START_APPROVED", "true", "INVOICE_APPROVED_DATABASE", LIVE_PIN))
                .run(context -> assertRefusedBeforeDataSource(context, "approval without profile",
                        "-Dspring.profiles.active=live"));
    }
}

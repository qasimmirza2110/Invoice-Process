package com.actify.invoice.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.actify.invoice.config.SchemaSafetyGuardTest.DATA_SOURCE_CREATED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SchemaSafetyGuard rule 4 for a hosted start (profile "render", the real application.properties and
 * application-render.properties, an approved database) and for the local demo: the free-tier queue is either off or
 * fully restricted (key, official endpoint, gemini-3.5-flash-lite, usable limits, safety margin, even pacing). There is
 * no paid mode and no invoice-id allowlist; left-over variables of the removed modes switch nothing on. Every refusal
 * happens before the DataSource exists and never shows the key. No database, no Gemini.
 */
class FreeTierStartupGuardTest {

    private static final String DB_URL = "jdbc:postgresql://ep-free-tier.example.test/invoice_free_tier_db?sslmode=require";
    private static final String DB_PIN = "ep-free-tier.example.test:5432/invoice_free_tier_db";
    private static final String FAKE_KEY = "free-tier-fake-key-that-must-never-appear";

    /** The approved hosted start every test begins with (no Gemini settings: extraction off). */
    private static final String[] HOSTED = {"INVOICE_DB_URL", DB_URL, "INVOICE_LIVE_START_APPROVED", "true",
            "INVOICE_APPROVED_DATABASE", DB_PIN};

    /** A complete free-tier queue for Render: no invoice id anywhere; example limits (the real ones come from AI Studio). */
    private static final String[] AUTO_QUEUE = {"INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED", "true",
            "GEMINI_API_KEY", FAKE_KEY, "GEMINI_MODEL", "gemini-3.5-flash-lite", "GEMINI_LIMIT_RPM", "5",
            "GEMINI_LIMIT_TPM", "50000", "GEMINI_LIMIT_RPD", "20", "GEMINI_INITIAL_TOKEN_ESTIMATE", "1033",
            "INVOICE_WORKER_ENABLED", "true"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchemaSafetyGuard.class, SchemaSafetyGuardTest.ProbeDataSourceConfig.class);

    @BeforeEach
    void reset() {
        DATA_SOURCE_CREATED.set(false);
    }

    /** application.properties overlaid with application-render.properties, profile "render", as on Render. */
    private ApplicationContextRunner hosted(String... environmentVariables) throws Exception {
        return withProfile("render", "application-render.properties", concat(HOSTED, environmentVariables));
    }

    /** application.properties overlaid with application-localdemo.properties, profile "localdemo". */
    private ApplicationContextRunner localDemo(String... environmentVariables) throws Exception {
        return withProfile(SchemaSafetyGuard.LOCAL_DEMO_PROFILE, "application-localdemo.properties", environmentVariables);
    }

    private ApplicationContextRunner withProfile(String profile, String profileFile, String[] environmentVariables)
            throws Exception {
        Properties merged = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        merged.putAll(PropertiesLoaderUtils.loadProperties(new ClassPathResource(profileFile)));
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<Object, Object> e : merged.entrySet()) {
            pairs.add(e.getKey() + "=" + e.getValue());
        }
        return runner.withInitializer(c -> c.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues(pairs.toArray(new String[0]))
                .withInitializer(environment(environmentVariables));
    }

    /** OS environment variables, with the same relaxed name mapping Spring uses for real ones (FOO_BAR -> foo.bar). */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> environment(String... keyValues) {
        Map<String, Object> variables = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            variables.put(keyValues[i], keyValues[i + 1]);
        }
        return c -> c.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("test-environment-variables", variables));
    }

    private static String[] concat(String[] a, String... b) {
        String[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    /** AUTO_QUEUE with one variable replaced (null = removed). */
    private static String[] autoQueueWith(String key, String value) {
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < AUTO_QUEUE.length; i += 2) {
            if (!AUTO_QUEUE[i].equals(key)) {
                pairs.add(AUTO_QUEUE[i]);
                pairs.add(AUTO_QUEUE[i + 1]);
            }
        }
        if (value != null) {
            pairs.add(key);
            pairs.add(value);
        }
        return pairs.toArray(new String[0]);
    }

    private static void assertStarted(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        assertThat(DATA_SOURCE_CREATED).isTrue();
    }

    private static void assertRefused(AssertableApplicationContext context, String why, String expected) {
        assertThat(context).as(why).hasFailed();
        String trace = stackTraceOf(context.getStartupFailure());
        assertThat(trace).as(why).contains("SchemaSafetyGuard: REFUSING to start").contains(expected);
        assertThat(trace).as(why + ": the key is never shown").doesNotContain(FAKE_KEY);
        assertThat(DATA_SOURCE_CREATED).as(why + ": refused before the DataSource").isFalse();
    }

    private void assertRefusedWith(String why, String expected, String... environmentVariables) throws Exception {
        DATA_SOURCE_CREATED.set(false);
        hosted(environmentVariables).run(context -> assertRefused(context, why, expected));
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    // ---- defaults: extraction stays off ----

    @Test
    void theShippedConfigurationKeepsExtractionOffAndHasNoPaidOrAllowlistSetting() throws Exception {
        Properties main = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        Properties render = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-render.properties"));
        Properties demo = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-localdemo.properties"));
        assertThat(main.getProperty("invoice.worker.enabled")).isEqualTo("${INVOICE_WORKER_ENABLED:false}");
        assertThat(render.getProperty("invoice.worker.enabled")).isEqualTo("${INVOICE_WORKER_ENABLED:false}");
        assertThat(main.getProperty(SchemaSafetyGuard.FREE_TIER_AUTO_QUEUE_PROPERTY))
                .isEqualTo("${INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED:false}");
        assertThat(main.getProperty("gemini.base-url")).isEqualTo(SchemaSafetyGuard.OFFICIAL_GEMINI_BASE_URL);
        assertThat(main.getProperty("gemini.model")).isEqualTo("${GEMINI_MODEL:gemini-3.5-flash-lite}");
        for (Properties p : new Properties[]{main, render, demo}) {
            for (String key : p.stringPropertyNames()) {
                String entry = (key + "=" + p.getProperty(key)).toLowerCase();
                assertThat(entry).as(key).doesNotContain("paid").doesNotContain("only-invoice-ids")
                        .doesNotContain("only_invoice_ids").doesNotContain("legacy-extract")
                        .doesNotContain("free-tier-test").doesNotContain("data-use-acknowledged");
            }
        }

        hosted().run(context -> {
            assertStarted(context);
            assertThat(context.getEnvironment().getProperty("invoice.worker.enabled", Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty(SchemaSafetyGuard.FREE_TIER_AUTO_QUEUE_PROPERTY, Boolean.class))
                    .isFalse();
        });
    }

    // ---- the free-tier queue ----

    @Test
    void aCompleteFreeTierQueueStartsWithoutAnyInvoiceId() throws Exception {
        hosted(AUTO_QUEUE).run(FreeTierStartupGuardTest::assertStarted);
        // Other limits read from AI Studio are accepted as configured - the application assumes no fixed quota.
        DATA_SOURCE_CREATED.set(false);
        hosted(concat(autoQueueWith("GEMINI_LIMIT_RPM", "30"), "GEMINI_LIMIT_TPM", "1000000", "GEMINI_LIMIT_RPD", "1500"))
                .run(FreeTierStartupGuardTest::assertStarted);
    }

    @Test
    void obsoleteVariablesOfRemovedModesNeitherStartAPaidPathNorBlockTheStartAndAreReported() throws Exception {
        String[] leftovers = {"INVOICE_GEMINI_PAID_TIER_CONFIRMED", "true", "INVOICE_GEMINI_REQUIRE_PAID_TIER", "false",
                "INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED", "true", "INVOICE_GEMINI_FREE_TIER_DATA_USE_ACKNOWLEDGED", "true",
                "INVOICE_WORKER_ONLY_INVOICE_IDS", "17", "INVOICE_LEGACY_EXTRACT_ENABLED", "true"};
        hosted(concat(AUTO_QUEUE, leftovers)).run(context -> {
            assertStarted(context);
            assertThat(SchemaSafetyGuard.obsoleteSettings(context.getEnvironment())).containsExactlyInAnyOrder(
                    "INVOICE_GEMINI_PAID_TIER_CONFIRMED", "INVOICE_GEMINI_REQUIRE_PAID_TIER",
                    "INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED", "INVOICE_GEMINI_FREE_TIER_DATA_USE_ACKNOWLEDGED",
                    "INVOICE_WORKER_ONLY_INVOICE_IDS", "INVOICE_LEGACY_EXTRACT_ENABLED");
        });
        // Without the free-tier confirmation the leftovers switch nothing on (and do not block the start).
        DATA_SOURCE_CREATED.set(false);
        hosted(leftovers).run(context -> {
            assertStarted(context);
            assertThat(context.getEnvironment().getProperty(SchemaSafetyGuard.FREE_TIER_AUTO_QUEUE_PROPERTY, Boolean.class))
                    .isFalse();
        });
        assertThat(SchemaSafetyGuard.obsoleteSettings(new MockEnvironment())).isEmpty();
    }

    @Test
    void theFreeTierQueueNeedsAKeyAndOnlyTheOfficialEndpoint() throws Exception {
        assertRefusedWith("no key", "GEMINI_API_KEY is not set", autoQueueWith("GEMINI_API_KEY", null));
        assertRefusedWith("other endpoint", "may only be sent to",
                concat(AUTO_QUEUE, "GEMINI_BASE_URL", "https://proxy.example.test/v1beta"));
        assertRefusedWith("http endpoint", "may only be sent to",
                concat(AUTO_QUEUE, "GEMINI_BASE_URL", "http://generativelanguage.googleapis.com/v1beta"));
        assertRefusedWith("unreadable flag", "must be true or false", "INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED", "maybe");
    }

    @Test
    void theModelMustBeGemini35FlashLite() throws Exception {
        assertRefusedWith("model not explicit", "needs GEMINI_MODEL=gemini-3.5-flash-lite", autoQueueWith("GEMINI_MODEL", null));
        assertRefusedWith("other model", "needs GEMINI_MODEL=gemini-3.5-flash-lite",
                autoQueueWith("GEMINI_MODEL", "gemini-3.5-pro"));
        DATA_SOURCE_CREATED.set(false);
        hosted(AUTO_QUEUE).withInitializer(c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                        "command-line", Collections.<String, Object>singletonMap("gemini.model", "some-other-model"))))
                .run(context -> assertRefused(context, "model overridden elsewhere", "nothing else may override"));
    }

    @Test
    void limitsMustBeWholePositiveNumbers() throws Exception {
        assertRefusedWith("no rpm", "GEMINI_LIMIT_RPM", autoQueueWith("GEMINI_LIMIT_RPM", null));
        assertRefusedWith("250k", "GEMINI_LIMIT_TPM", autoQueueWith("GEMINI_LIMIT_TPM", "250k"));
        assertRefusedWith("no rpd", "GEMINI_LIMIT_RPD", autoQueueWith("GEMINI_LIMIT_RPD", null));
        assertRefusedWith("rpd 0", "GEMINI_LIMIT_RPD", autoQueueWith("GEMINI_LIMIT_RPD", "0"));
        assertRefusedWith("no estimate", "GEMINI_INITIAL_TOKEN_ESTIMATE", autoQueueWith("GEMINI_INITIAL_TOKEN_ESTIMATE", null));
    }

    @Test
    void theSafetyMarginAndEvenPacingCannotBeLoosened() throws Exception {
        assertRefusedWith("margin 0.9", "safety-margin may not be above 0.8",
                concat(AUTO_QUEUE, "GEMINI_LIMITS_SAFETY_MARGIN", "0.9"));
        assertRefusedWith("margin 1", "safety-margin may not be above 0.8", concat(AUTO_QUEUE, "GEMINI_LIMITS_SAFETY_MARGIN", "1"));
        assertRefusedWith("no even pacing", "even-pacing must stay true", concat(AUTO_QUEUE, "GEMINI_LIMITS_EVEN_PACING", "false"));
        DATA_SOURCE_CREATED.set(false);
        hosted(concat(AUTO_QUEUE, "GEMINI_LIMITS_SAFETY_MARGIN", "0.5")).run(FreeTierStartupGuardTest::assertStarted);
    }

    // ---- the local demo: off by default, the same free-tier rules when opted in ----

    @Test
    void theLocalDemoUsesTheSameFreeTierRulesWhenOptedIn() throws Exception {
        localDemo(AUTO_QUEUE).run(FreeTierStartupGuardTest::assertStarted);
        DATA_SOURCE_CREATED.set(false);
        localDemo(autoQueueWith("GEMINI_MODEL", "gemini-3.5-pro"))
                .run(context -> assertRefused(context, "local demo, other model", "needs GEMINI_MODEL=gemini-3.5-flash-lite"));
        DATA_SOURCE_CREATED.set(false);
        localDemo("GEMINI_API_KEY", FAKE_KEY)
                .run(context -> assertRefused(context, "local demo key without opt-in", "must not have a Gemini API key"));
        DATA_SOURCE_CREATED.set(false);
        localDemo("INVOICE_WORKER_ENABLED", "true")
                .run(context -> assertRefused(context, "local demo worker without opt-in", "must not enable the extraction worker"));
        DATA_SOURCE_CREATED.set(false);
        localDemo().run(FreeTierStartupGuardTest::assertStarted);
    }
}

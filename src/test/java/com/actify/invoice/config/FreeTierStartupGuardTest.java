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
 * application-render.properties, an approved database): the paid-tier requirement cannot be switched off, the paid and
 * free-tier confirmations exclude each other, and FREE_TIER_TEST is accepted only fully restricted. Every refusal
 * happens before the DataSource exists and never shows the key. No database, no Gemini.
 */
class FreeTierStartupGuardTest {

    private static final String DB_URL = "jdbc:postgresql://ep-free-tier.example.test/invoice_free_tier_db?sslmode=require";
    private static final String DB_PIN = "ep-free-tier.example.test:5432/invoice_free_tier_db";
    private static final String FAKE_KEY = "free-tier-fake-key-that-must-never-appear";

    /** The approved hosted start every test begins with (no Gemini settings: extraction off). */
    private static final String[] HOSTED = {"INVOICE_DB_URL", DB_URL, "INVOICE_LIVE_START_APPROVED", "true",
            "INVOICE_APPROVED_DATABASE", DB_PIN};

    /** A complete, valid free-tier test as proposed for Render (well below the reported ceilings). */
    private static final String[] FREE_TIER = {"INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED", "true",
            "GEMINI_API_KEY", FAKE_KEY, "GEMINI_MODEL", "gemini-3.5-flash-lite", "GEMINI_LIMIT_RPM", "5",
            "GEMINI_LIMIT_TPM", "50000", "GEMINI_LIMIT_RPD", "20", "GEMINI_INITIAL_TOKEN_ESTIMATE", "5000",
            "INVOICE_WORKER_ENABLED", "true", "INVOICE_WORKER_ONLY_INVOICE_IDS", "17"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchemaSafetyGuard.class, SchemaSafetyGuardTest.ProbeDataSourceConfig.class);

    @BeforeEach
    void reset() {
        DATA_SOURCE_CREATED.set(false);
    }

    /** application.properties overlaid with application-render.properties, profile "render", as on Render. */
    private ApplicationContextRunner hosted(String... environmentVariables) throws Exception {
        Properties merged = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        merged.putAll(PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-render.properties")));
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<Object, Object> e : merged.entrySet()) {
            pairs.add(e.getKey() + "=" + e.getValue());
        }
        return runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("render"))
                .withPropertyValues(pairs.toArray(new String[0]))
                .withInitializer(environment(concat(HOSTED, environmentVariables)));
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

    /** FREE_TIER with one variable replaced (null = removed). */
    private static String[] freeTierWith(String key, String value) {
        List<String> pairs = new ArrayList<>();
        for (int i = 0; i < FREE_TIER.length; i += 2) {
            if (!FREE_TIER[i].equals(key)) {
                pairs.add(FREE_TIER[i]);
                pairs.add(FREE_TIER[i + 1]);
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
    void theShippedConfigurationKeepsEveryExtractionSwitchOff() throws Exception {
        Properties main = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        Properties render = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-render.properties"));
        assertThat(main.getProperty("invoice.worker.enabled")).isEqualTo("${INVOICE_WORKER_ENABLED:false}");
        assertThat(render.getProperty("invoice.worker.enabled")).isEqualTo("${INVOICE_WORKER_ENABLED:false}");
        assertThat(main.getProperty(SchemaSafetyGuard.REQUIRE_PAID_TIER_PROPERTY)).isEqualTo("true");
        assertThat(main.getProperty(SchemaSafetyGuard.PAID_TIER_CONFIRMED_PROPERTY))
                .isEqualTo("${INVOICE_GEMINI_PAID_TIER_CONFIRMED:false}");
        assertThat(main.getProperty(SchemaSafetyGuard.FREE_TIER_TEST_PROPERTY))
                .isEqualTo("${INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED:false}");
        assertThat(render.getProperty(SchemaSafetyGuard.LEGACY_EXTRACT_PROPERTY))
                .isEqualTo("${INVOICE_LEGACY_EXTRACT_ENABLED:false}");
        assertThat(main.getProperty("gemini.base-url")).isEqualTo(SchemaSafetyGuard.OFFICIAL_GEMINI_BASE_URL);

        hosted().run(context -> {
            assertStarted(context);
            assertThat(context.getEnvironment().getProperty("invoice.worker.enabled", Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty(SchemaSafetyGuard.FREE_TIER_TEST_PROPERTY, Boolean.class)).isFalse();
        });
    }

    @Test
    void aCompleteFreeTierTestStartsAndAlsoAtTheReportedCeilings() throws Exception {
        hosted(FREE_TIER).run(FreeTierStartupGuardTest::assertStarted);
        DATA_SOURCE_CREATED.set(false);
        hosted(concat(freeTierWith("GEMINI_LIMIT_RPM", "15"), "GEMINI_LIMIT_TPM", "250000", "GEMINI_LIMIT_RPD", "500"))
                .run(FreeTierStartupGuardTest::assertStarted);
        DATA_SOURCE_CREATED.set(false);
        // The restrictions hold whether or not the worker is switched on.
        hosted(freeTierWith("INVOICE_WORKER_ENABLED", null)).run(FreeTierStartupGuardTest::assertStarted);
    }

    // ---- the two confirmations ----

    @Test
    void paidAndFreeTierConfirmationsCannotBeCombined() throws Exception {
        assertRefusedWith("both", "are both true", concat(FREE_TIER, "INVOICE_GEMINI_PAID_TIER_CONFIRMED", "true"));
        assertRefusedWith("both, other spellings", "are both true",
                concat(freeTierWith("INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED", "on"), "INVOICE_GEMINI_PAID_TIER_CONFIRMED", "yes"));
    }

    @Test
    void thePaidTierRequirementCannotBeSwitchedOffOutsideTheLocalDemo() throws Exception {
        for (String off : new String[]{"false", "FALSE", "no", "off", "0"}) {
            assertRefusedWith("require-paid-tier=" + off, "must stay true outside the local demo",
                    "INVOICE_GEMINI_REQUIRE_PAID_TIER", off);
        }
        assertRefusedWith("free tier + require off", "must stay true outside the local demo",
                concat(FREE_TIER, "INVOICE_GEMINI_REQUIRE_PAID_TIER", "false"));
        assertRefusedWith("unreadable flag", "must be true or false", "INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED", "maybe");
    }

    @Test
    void paidModeOnItsOwnIsUnchangedAndNeedsNoAllowlist() throws Exception {
        hosted("INVOICE_GEMINI_PAID_TIER_CONFIRMED", "true", "GEMINI_API_KEY", FAKE_KEY, "INVOICE_WORKER_ENABLED", "true")
                .run(FreeTierStartupGuardTest::assertStarted);
    }

    // ---- exactly one invoice ----

    @Test
    void theFreeTierTestNeedsExactlyOneInvoiceId() throws Exception {
        String[] refused = {"", " ", "17,18", "17, 18", "17 18", "abc", "0", "-1", "17;18", "1e3", "*"};
        for (String ids : refused) {
            assertRefusedWith("ids '" + ids + "'", "exactly ONE invoice", freeTierWith("INVOICE_WORKER_ONLY_INVOICE_IDS", ids));
        }
        assertRefusedWith("no ids", "exactly ONE invoice", freeTierWith("INVOICE_WORKER_ONLY_INVOICE_IDS", null));
        assertRefusedWith("no ids, worker off", "exactly ONE invoice",
                concat(freeTierWith("INVOICE_WORKER_ONLY_INVOICE_IDS", null), "INVOICE_WORKER_ENABLED", "false"));
        // "yes" switches the mode on exactly as "true" would (the @Value conversion) - so it is checked the same way.
        assertRefusedWith("'yes' spelling", "exactly ONE invoice",
                concat(freeTierWith("INVOICE_WORKER_ONLY_INVOICE_IDS", null), "INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED", "yes"));
        DATA_SOURCE_CREATED.set(false);
        hosted(freeTierWith("INVOICE_WORKER_ONLY_INVOICE_IDS", " 42 ")).run(FreeTierStartupGuardTest::assertStarted);
    }

    // ---- key, endpoint, model ----

    @Test
    void theFreeTierTestNeedsAKey() throws Exception {
        assertRefusedWith("no key", "GEMINI_API_KEY is not set", freeTierWith("GEMINI_API_KEY", null));
    }

    @Test
    void theKeyMayOnlyGoToTheOfficialEndpoint() throws Exception {
        assertRefusedWith("other endpoint", "may only be sent to",
                concat(FREE_TIER, "GEMINI_BASE_URL", "https://attacker.example.test/v1beta"));
        assertRefusedWith("http endpoint", "may only be sent to",
                concat(FREE_TIER, "GEMINI_BASE_URL", "http://generativelanguage.googleapis.com/v1beta"));
    }

    @Test
    void theModelMustBeTheOneWhoseFreeTierLimitsWereReported() throws Exception {
        assertRefusedWith("no model", "GEMINI_MODEL=gemini-3.5-flash-lite", freeTierWith("GEMINI_MODEL", null));
        assertRefusedWith("other model", "GEMINI_MODEL=gemini-3.5-flash-lite", freeTierWith("GEMINI_MODEL", "gemini-3.5-pro"));
        DATA_SOURCE_CREATED.set(false);
        hosted(FREE_TIER).withInitializer(c -> c.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                        "command-line", Collections.<String, Object>singletonMap("gemini.model", "some-other-model"))))
                .run(context -> assertRefused(context, "model overridden elsewhere", "nothing else may override"));
    }

    // ---- limits ----

    @Test
    void limitsMayNotExceedTheReportedFreeTierCeilings() throws Exception {
        assertRefusedWith("rpm 16", "may not exceed 15 / 250000 / 500", freeTierWith("GEMINI_LIMIT_RPM", "16"));
        assertRefusedWith("tpm 250001", "may not exceed 15 / 250000 / 500", freeTierWith("GEMINI_LIMIT_TPM", "250001"));
        assertRefusedWith("rpd 501", "may not exceed 15 / 250000 / 500", freeTierWith("GEMINI_LIMIT_RPD", "501"));
        assertRefusedWith("rpd 1000", "may not exceed 15 / 250000 / 500", freeTierWith("GEMINI_LIMIT_RPD", "1000"));
    }

    @Test
    void limitsMustBeWholePositiveNumbers() throws Exception {
        assertRefusedWith("no rpm", "GEMINI_LIMIT_RPM", freeTierWith("GEMINI_LIMIT_RPM", null));
        assertRefusedWith("250k", "GEMINI_LIMIT_TPM", freeTierWith("GEMINI_LIMIT_TPM", "250k"));
        assertRefusedWith("rpd 0", "GEMINI_LIMIT_RPD", freeTierWith("GEMINI_LIMIT_RPD", "0"));
        assertRefusedWith("no estimate", "GEMINI_INITIAL_TOKEN_ESTIMATE", freeTierWith("GEMINI_INITIAL_TOKEN_ESTIMATE", null));
    }

    @Test
    void theSafetyMarginAndEvenPacingCannotBeLoosened() throws Exception {
        assertRefusedWith("margin 0.9", "safety-margin may not be above 0.8",
                concat(FREE_TIER, "GEMINI_LIMITS_SAFETY_MARGIN", "0.9"));
        assertRefusedWith("margin 1", "safety-margin may not be above 0.8", concat(FREE_TIER, "GEMINI_LIMITS_SAFETY_MARGIN", "1"));
        assertRefusedWith("no even pacing", "even-pacing must stay true", concat(FREE_TIER, "GEMINI_LIMITS_EVEN_PACING", "false"));
        DATA_SOURCE_CREATED.set(false);
        hosted(concat(FREE_TIER, "GEMINI_LIMITS_SAFETY_MARGIN", "0.5")).run(FreeTierStartupGuardTest::assertStarted);
    }

    // ---- the legacy endpoint ----

    @Test
    void theLegacySingleFileExtractionCannotBypassTheAllowlist() throws Exception {
        assertRefusedWith("legacy on", "legacy single-file extraction must stay disabled",
                concat(FREE_TIER, "INVOICE_LEGACY_EXTRACT_ENABLED", "true"));
        assertRefusedWith("legacy on, other spelling", "legacy single-file extraction must stay disabled",
                concat(FREE_TIER, "INVOICE_LEGACY_EXTRACT_ENABLED", "yes"));
    }

    // ---- the local demo keeps its own opt-in ----

    @Test
    void theLocalDemoRefusesTheHostedFreeTierFlag() throws Exception {
        Properties merged = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        merged.putAll(PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-localdemo.properties")));
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<Object, Object> e : merged.entrySet()) {
            pairs.add(e.getKey() + "=" + e.getValue());
        }
        runner.withInitializer(c -> c.getEnvironment().setActiveProfiles(SchemaSafetyGuard.LOCAL_DEMO_PROFILE))
                .withPropertyValues(pairs.toArray(new String[0]))
                .withInitializer(environment("INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED", "true"))
                .run(context -> assertRefused(context, "local demo", "must not set INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED"));
    }
}

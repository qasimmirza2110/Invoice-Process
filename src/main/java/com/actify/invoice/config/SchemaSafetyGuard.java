package com.actify.invoice.config;

import com.actify.invoice.quota.QuotaSettings;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Fail-closed startup guard that protects the live database from accidental schema changes.
 *
 * WHEN does it run? Spring calls every BeanFactoryPostProcessor "before any other beans get created"
 * (Spring Framework Javadoc). So this check happens before the DataSource, the EntityManagerFactory and
 * Hibernate exist - before any database connection and before any DDL. An ApplicationRunner,
 * CommandLineRunner or @PostConstruct would run AFTER Hibernate, which is too late.
 *
 * WHAT does it read? Only configuration (the Spring Environment). It never connects to a database.
 *
 * RULES
 *  1. The PostgreSQL test run (profile exactly "pgtest" AND exactly localhost:5432/<test database>) is allowed,
 *     including ddl-auto=update. PgTestDatabaseGuard then double-checks current_database() before Hibernate runs.
 *  1b. The local demo (profile exactly "localdemo") has its own, stricter rules - see checkLocalDemo. It never uses
 *     the approval variables below and can only ever reach localhost:5432/invoice_process_local_demo_db.
 *     application.properties makes "localdemo" the DEFAULT profile (spring.profiles.default), so a plain start with
 *     no profile at all (Eclipse "Run As > Java Application") is a local-demo start and gets these same rules.
 *  2. Every other start - one with an explicit other profile, e.g. -Dspring.profiles.active=live - is refused unless
 *     the launch configuration sets INVOICE_LIVE_START_APPROVED=true AND INVOICE_APPROVED_DATABASE=host:port/database
 *     matching the JDBC URL.
 *     Each start is approved for ONE named database (a scratch rehearsal copy, or later the live database) in its
 *     own approval gate.
 *  3. Even with that approval, no setting may let Hibernate or Spring change the schema: ddl-auto must be
 *     validate/none, and the other schema-generation / SQL-init switches (also as bare -D system properties)
 *     must be off.
 *  4. Gemini free-tier queue (every start, see checkFreeTierQueue): with INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED=true
 *     the start needs a key, the official endpoint only, GEMINI_MODEL=gemini-3.5-flash-lite, usable GEMINI_LIMIT_*
 *     values, a safety margin of at most 0.8 and even pacing. There is no paid mode and no invoice-id allowlist; left-over
 *     variables of the removed modes are reported as a warning (obsoleteSettings) and have no effect.
 *
 * Not proven by a database: proven by SchemaSafetyGuardTest, which shows that a refused start never even
 * calls the DataSource factory method.
 */
@Component
public class SchemaSafetyGuard implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    public static final String TEST_DATABASE = "Actify-Internship-Invoice-Process_test_db";
    public static final String TEST_PROFILE = "pgtest";
    static final String TEST_ENDPOINT = "localhost:5432/" + TEST_DATABASE;
    /** The real (live) database. The local demo refuses it by name, whatever else is configured. */
    public static final String LIVE_DATABASE = "Actify-Internship-Invoice-Process_db";
    public static final String LOCAL_DEMO_PROFILE = "localdemo";
    public static final String LOCAL_DEMO_DATABASE = "invoice_process_local_demo_db";
    static final String LOCAL_DEMO_ENDPOINT = "localhost:5432/" + LOCAL_DEMO_DATABASE;
    public static final String APPROVAL_PROPERTY = "invoice.live-start-approved";
    public static final String APPROVED_DATABASE_PROPERTY = "invoice.approved-database";
    static final String OFFICIAL_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    /** INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED: the ONE switch that lets consented uploads reach Gemini. */
    public static final String FREE_TIER_AUTO_QUEUE_PROPERTY = "invoice.gemini.free-tier-auto-queue-confirmed";
    /** The only model this application uses. */
    public static final String FREE_TIER_MODEL = "gemini-3.5-flash-lite";
    /** The worker never uses more than the default 80 % of a configured limit. */
    public static final double FREE_TIER_MAX_SAFETY_MARGIN = 0.8;
    /**
     * Environment variables of removed features (paid mode, one-invoice test mode, invoice-id allowlist, data-use flag,
     * legacy direct extraction, local-demo opt-in). Nothing reads them any more; a start that still
     * has one logs a warning so the operator can delete it.
     */
    static final List<String> OBSOLETE_VARIABLES = Arrays.asList(
            "INVOICE_GEMINI_PAID_TIER_CONFIRMED", "INVOICE_GEMINI_REQUIRE_PAID_TIER",
            "INVOICE_GEMINI_FREE_TIER_TEST_CONFIRMED", "INVOICE_GEMINI_FREE_TIER_DATA_USE_ACKNOWLEDGED",
            "INVOICE_WORKER_ONLY_INVOICE_IDS", "INVOICE_LEGACY_EXTRACT_ENABLED", "INVOICE_LOCALDEMO_GEMINI_ENABLED");

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        check(environment);
        List<String> obsolete = obsoleteSettings(environment);
        if (!obsolete.isEmpty()) {
            // Names only, never values. They have no effect; the operator should delete them from the environment.
            LoggerFactory.getLogger(SchemaSafetyGuard.class).warn("Obsolete environment variable(s) {} are set but are "
                    + "no longer read (paid mode, one-invoice test mode and invoice-id allowlists were removed). Delete "
                    + "them from the service environment.", obsolete);
        }
    }

    /** Which OBSOLETE_VARIABLES are present in the environment (names only). */
    static List<String> obsoleteSettings(Environment env) {
        List<String> present = new ArrayList<>();
        for (String name : OBSOLETE_VARIABLES) {
            if (env.getProperty(name) != null) {
                present.add(name);
            }
        }
        return present;
    }

    /** Static and side-effect free, so it can be unit-tested with any Environment. */
    public static void check(Environment env) {
        if (isIsolatedTestRun(env)) {
            return; // rule 1 (PgTestDatabaseGuard then checks the REAL connection with current_database())
        }
        // Every rule below approves spring.datasource.url - so nothing may replace that URL behind its back.
        requireNoDataSourceRedirect(env);
        if (Arrays.asList(effectiveProfiles(env)).contains(LOCAL_DEMO_PROFILE)) {
            checkLocalDemo(env); // rule 1b
            return;
        }
        if (!"true".equalsIgnoreCase(env.getProperty(APPROVAL_PROPERTY, "false").trim())) {
            throw refuse("starting against a non-test database is not approved. For local work start WITHOUT a profile "
                    + "(the local demo is the default) or use the 'Invoice Process - localdemo' launch configuration. "
                    + "A real database first needs a fresh, restore-tested backup and the reviewed schema script; then "
                    + "the approved launch configuration sets INVOICE_LIVE_START_APPROVED=true and "
                    + "INVOICE_APPROVED_DATABASE=host:port/database");
        }
        requireNonMutatingSchemaSettings(env);
        requireApprovedEndpoint(env);
        checkGeminiModes(env); // rule 4
    }

    /**
     * Local demo: a separate, disposable database on this computer, never the live or the test database.
     * Fail-closed on every point; nothing here needs (or accepts) the live-start approval variables.
     * Messages never contain secret values - only property names and host:port/database.
     */
    static void checkLocalDemo(Environment env) {
        if (!Arrays.equals(effectiveProfiles(env), new String[]{LOCAL_DEMO_PROFILE})) {
            throw refuse("the local demo needs exactly one active profile, [" + LOCAL_DEMO_PROFILE + "], not "
                    + Arrays.toString(effectiveProfiles(env)));
        }
        if ("true".equalsIgnoreCase(env.getProperty(APPROVAL_PROPERTY, "false").trim())
                || !env.getProperty(APPROVED_DATABASE_PROPERTY, "").trim().isEmpty()) {
            throw refuse("the local demo must not set INVOICE_LIVE_START_APPROVED or INVOICE_APPROVED_DATABASE; "
                    + "remove them from the launch configuration (an approved non-demo start also needs an explicit "
                    + "profile, e.g. -Dspring.profiles.active=live, because the local demo is the default profile)");
        }
        String actual = endpointOf(env.getProperty("spring.datasource.url", ""));
        if (actual == null) {
            throw refuse("spring.datasource.url is not a single-host jdbc:postgresql://host[:port]/database URL");
        }
        String database = actual.substring(actual.indexOf('/') + 1);
        if (database.equalsIgnoreCase(LIVE_DATABASE) || database.equalsIgnoreCase(TEST_DATABASE)) {
            throw refuse("the local demo may never use " + database + "; remove INVOICE_DB_URL / SPRING_DATASOURCE_URL "
                    + "overrides from the launch configuration");
        }
        if (!actual.equals(LOCAL_DEMO_ENDPOINT)) {
            throw refuse("the local demo datasource points at " + actual + " but only " + LOCAL_DEMO_ENDPOINT
                    + " is allowed");
        }
        requireNonMutatingSchemaSettings(env);
        checkLocalDemoGemini(env);
    }

    /**
     * By default the local demo cannot call Gemini: no key (GEMINI_API_KEY would override the profile file) and no
     * worker. INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED=true is the explicit opt-in for a local extraction test with
     * a free-tier key; then the same rules as on the server apply (checkFreeTierQueue) and, as everywhere, only uploads
     * with free-tier consent are sent. The database rules above apply unchanged: only the local demo database is used.
     */
    static void checkLocalDemoGemini(Environment env) {
        if (flag(env, FREE_TIER_AUTO_QUEUE_PROPERTY, false)) {
            checkFreeTierQueue(env);
            return;
        }
        if (!env.getProperty("gemini.api-key", "").trim().isEmpty()) {
            throw refuse("the local demo must not have a Gemini API key; remove GEMINI_API_KEY from the launch "
                    + "configuration (or opt in deliberately with INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED=true)");
        }
        // Read exactly as ExtractionWorker's @Value reads it: "yes", "on" and "1" also switch the worker on.
        if (flag(env, "invoice.worker.enabled", false)) {
            throw refuse("the local demo must not enable the extraction worker (INVOICE_WORKER_ENABLED) unless "
                    + "INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED=true");
        }
    }

    /** Limits are validated at startup instead of leaving a silently idle worker. Messages name properties only. */
    private static QuotaSettings requireUsableLimits(Environment env) {
        double safetyMargin;
        try {
            safetyMargin = Double.parseDouble(env.getProperty("gemini.limits.safety-margin", "0.8").trim());
        } catch (NumberFormatException e) {
            throw refuse("gemini.limits.safety-margin must be a number");
        }
        QuotaSettings limits = new QuotaSettings(env.getProperty("gemini.limits.rpm", ""),
                env.getProperty("gemini.limits.tpm", ""), env.getProperty("gemini.limits.rpd", ""),
                env.getProperty("gemini.limits.initial-token-estimate", ""), safetyMargin);
        if (!limits.isConfigured()) {
            throw refuse("the Gemini limits are not usable: " + limits.getProblem() + " Use whole numbers read from "
                    + "Google AI Studio for this project and model (e.g. 250000, not 250k)");
        }
        return limits;
    }

    /**
     * Rule 4 (every non-demo start): the free-tier queue is either off (nothing is sent) or fully restricted.
     * Booleans are read with the same conversion @Value uses ("yes"/"on"/"1" also mean true), so no spelling can
     * switch it on behind the guard's back.
     */
    static void checkGeminiModes(Environment env) {
        if (flag(env, FREE_TIER_AUTO_QUEUE_PROPERTY, false)) {
            checkFreeTierQueue(env);
        }
    }

    /**
     * INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED=true: consented uploads through a free-tier key, FIFO (the per-record
     * consent filter is ExtractionJobService). Fail-closed: a key for the official endpoint only, the one model this
     * application uses, usable limits (whatever the operator read in AI Studio - no value is assumed here) with at most
     * the default safety margin, and even pacing.
     */
    static void checkFreeTierQueue(Environment env) {
        String variable = "INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED";
        if (env.getProperty("gemini.api-key", "").trim().isEmpty()) {
            throw refuse(variable + "=true but GEMINI_API_KEY is not set");
        }
        if (!OFFICIAL_GEMINI_BASE_URL.equals(env.getProperty("gemini.base-url", "").trim())) {
            throw refuse("the Gemini key may only be sent to " + OFFICIAL_GEMINI_BASE_URL
                    + "; remove any gemini.base-url / GEMINI_BASE_URL override");
        }
        if (!FREE_TIER_MODEL.equals(env.getProperty("GEMINI_MODEL", "").trim())
                || !FREE_TIER_MODEL.equals(env.getProperty("gemini.model", "").trim())) {
            throw refuse(variable + "=true needs GEMINI_MODEL=" + FREE_TIER_MODEL + " set explicitly (the only model "
                    + "this application uses), and nothing else may override gemini.model");
        }
        QuotaSettings limits = requireUsableLimits(env);
        if (limits.getSafetyMargin() > FREE_TIER_MAX_SAFETY_MARGIN) {
            throw refuse("gemini.limits.safety-margin may not be above " + FREE_TIER_MAX_SAFETY_MARGIN);
        }
        if (!flag(env, "gemini.limits.even-pacing", true)) {
            throw refuse("gemini.limits.even-pacing must stay true");
        }
    }

    /** A boolean property read exactly as @Value reads it; an unreadable value refuses the start. */
    private static boolean flag(Environment env, String key, boolean defaultValue) {
        try {
            Boolean value = env.getProperty(key, Boolean.class, defaultValue);
            return value == null ? defaultValue : value;
        } catch (RuntimeException e) {
            throw refuse(key + " must be true or false");
        }
    }

    /** Rule 3: no setting may let Hibernate or Spring change the schema. */
    /**
     * Spring Boot binds spring.datasource.hikari.* onto the pool AFTER spring.datasource.url, so a Hikari jdbc-url
     * (e.g. the environment variable SPRING_DATASOURCE_HIKARI_JDBCURL) silently replaces the URL approved below; a
     * data-source class/JNDI name or a JNDI/other pool type bypasses the URL entirely. None of these is used by this
     * application, so each is refused. Looked up through Spring's relaxed Binder - the same rules Spring Boot uses to
     * configure the pool - so every environment-variable spelling is caught. Values are never echoed.
     * Proven by DataSourceRedirectGuardTest.
     */
    static void requireNoDataSourceRedirect(Environment env) {
        if (!(env instanceof ConfigurableEnvironment)) {
            return; // only possible in hand-made test environments; Spring always passes a ConfigurableEnvironment
        }
        Binder binder = Binder.get(env);
        for (String key : DATA_SOURCE_REDIRECT_KEYS) {
            if (binder.bind(key, Bindable.of(String.class)).isBound()) {
                throw refuse(key + " is set. It would redirect the database connection away from the approved "
                        + "spring.datasource.url (INVOICE_DB_URL); remove it and put connection options into the URL");
            }
        }
        if (binder.bind("spring.datasource.hikari.data-source-properties",
                Bindable.mapOf(String.class, String.class)).isBound()) {
            throw refuse("spring.datasource.hikari.data-source-properties is set. It could redirect the database "
                    + "connection away from the approved spring.datasource.url; put connection options into the URL");
        }
    }

    private static final String[] DATA_SOURCE_REDIRECT_KEYS = {
            "spring.datasource.hikari.jdbc-url",
            "spring.datasource.hikari.data-source-class-name",
            "spring.datasource.hikari.data-source-j-n-d-i",
            "spring.datasource.jndi-name",
            "spring.datasource.type",
    };

    static void requireNonMutatingSchemaSettings(Environment env) {
        requireOneOf(env, "spring.jpa.hibernate.ddl-auto", "validate", "none");
        requireOneOf(env, "spring.jpa.properties.hibernate.hbm2ddl.auto", "validate", "none");
        requireOneOf(env, "spring.jpa.properties.javax.persistence.schema-generation.database.action", "none");
        requireOneOf(env, "spring.jpa.properties.jakarta.persistence.schema-generation.database.action", "none");
        // Hibernate also reads these keys directly from JVM system properties (-D...), outside spring.jpa.*.
        // The Spring Environment contains system properties and environment variables, so this covers them too.
        requireOneOf(env, "hibernate.hbm2ddl.auto", "validate", "none");
        requireOneOf(env, "javax.persistence.schema-generation.database.action", "none");
        requireOneOf(env, "jakarta.persistence.schema-generation.database.action", "none");
        requireOneOf(env, "spring.jpa.generate-ddl", "false");
        requireOneOf(env, "spring.sql.init.mode", "never", "embedded");
        requireOneOf(env, "spring.datasource.initialization-mode", "never", "embedded"); // deprecated alias
    }

    /**
     * The approval flag alone would allow ANY database. So a non-test start must also name the one database it is
     * approved for (INVOICE_APPROVED_DATABASE = host:port/database), and that must equal the endpoint written in the
     * JDBC URL - e.g. a restored scratch copy during the rehearsal, the live database only after its own approval.
     *
     * Limits (honest): this compares CONFIGURATION text, without connecting. It proves the URL points at the approved
     * name, not which physical server answers there; "localhost" and "127.0.0.1" count as different spellings.
     * Only the parsed host:port/database is ever shown in a message - never the full URL (it may hold credentials).
     */
    static void requireApprovedEndpoint(Environment env) {
        String approved = env.getProperty(APPROVED_DATABASE_PROPERTY, "").trim();
        if (approved.isEmpty()) {
            throw refuse(APPROVED_DATABASE_PROPERTY + " (INVOICE_APPROVED_DATABASE) is not set: name the exact approved "
                    + "database as host:port/database");
        }
        String expected = normalizeEndpoint(approved);
        if (expected == null) {
            throw refuse(APPROVED_DATABASE_PROPERTY + " must look like host:port/database");
        }
        String actual = endpointOf(env.getProperty("spring.datasource.url", ""));
        if (actual == null) {
            throw refuse("spring.datasource.url is not a single-host jdbc:postgresql://host[:port]/database URL");
        }
        if (!actual.equals(expected)) {
            throw refuse("the datasource points at " + actual + " but only " + expected + " is approved");
        }
    }

    /** "jdbc:postgresql://Host:5432/db?x=y" -> "host:5432/db". null when it is not a simple single-host URL. */
    static String endpointOf(String jdbcUrl) {
        String prefix = "jdbc:postgresql://";
        if (jdbcUrl == null || !jdbcUrl.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            return null;
        }
        String rest = jdbcUrl.substring(prefix.length());
        int query = rest.indexOf('?');
        return normalizeEndpoint(query >= 0 ? rest.substring(0, query) : rest);
    }

    /** "host[:port]/database" -> "host:port/database" (lower-case host, default port 5432), or null. */
    static String normalizeEndpoint(String hostPortDatabase) {
        int slash = hostPortDatabase.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String hostPort = hostPortDatabase.substring(0, slash);
        String database = hostPortDatabase.substring(slash + 1);
        // Multi-host failover URLs, user@host and nested paths are refused rather than guessed.
        if (database.isEmpty() || database.contains("/") || hostPort.contains(",") || hostPort.contains("@")
                || hostPort.contains("[")) {
            return null;
        }
        int colon = hostPort.lastIndexOf(':');
        String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
        String port = colon < 0 ? "5432" : hostPort.substring(colon + 1);
        if (host.isEmpty() || !port.matches("\\d{1,5}")) {
            return null;
        }
        return host.toLowerCase(Locale.ROOT) + ":" + Integer.parseInt(port) + "/" + database;
    }

    /** The profiles Spring really uses: the active ones or, when none is active, the default ones. */
    static String[] effectiveProfiles(Environment env) {
        String[] active = env.getActiveProfiles();
        return active.length > 0 ? active : env.getDefaultProfiles();
    }

    static boolean isIsolatedTestRun(Environment env) {
        if (!Arrays.equals(env.getActiveProfiles(), new String[]{TEST_PROFILE})) {
            return false;
        }
        // Host and port are pinned too, not only the database name.
        return TEST_ENDPOINT.equals(endpointOf(env.getProperty("spring.datasource.url", "")));
    }

    /**
     * Unset is fine (Spring Boot's default for a non-embedded database is to change nothing). EVERY value is checked,
     * not just the one that wins: the resolved value (also covers JVM -D properties and SPRING_... environment
     * variables) plus each entry, in every property source, whose name is another spelling of the key - ddlAuto,
     * ddl_auto, DDL-AUTO or [bracket] map keys - so two spellings in one file cannot hide each other.
     */
    private static void requireOneOf(Environment env, String key, String... allowed) {
        List<String> values = new ArrayList<>();
        values.add(env.getProperty(key));
        if (env instanceof ConfigurableEnvironment) {
            String wanted = relaxed(key);
            for (PropertySource<?> source : ((ConfigurableEnvironment) env).getPropertySources()) {
                if (!(source instanceof EnumerablePropertySource)) {
                    continue;
                }
                for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
                    Object raw = source.getProperty(name);
                    if (raw != null && relaxed(name).equals(wanted)) {
                        values.add(env.resolvePlaceholders(String.valueOf(raw)));
                    }
                }
            }
        }
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!Arrays.asList(allowed).contains(normalized)) {
                throw refuse(key + "=" + normalized + " could change the database schema; allowed: "
                        + String.join(", ", allowed));
            }
        }
    }

    /** "spring.jpa.hibernate.ddlAuto" / "...ddl_auto" / "...[ddl-auto]" -> "spring.jpa.hibernate.ddlauto". */
    static String relaxed(String name) {
        return name.toLowerCase(Locale.ROOT).replace("[", ".").replace("]", "").replace("-", "").replace("_", "");
    }

    private static IllegalStateException refuse(String reason) {
        return new IllegalStateException("SchemaSafetyGuard: REFUSING to start - " + reason + ".");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

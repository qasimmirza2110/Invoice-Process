package com.actify.invoice.config;

import com.actify.invoice.quota.QuotaSettings;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
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
    /** Explicit local-demo opt-in for real Gemini calls (INVOICE_LOCALDEMO_GEMINI_ENABLED). */
    public static final String LOCAL_DEMO_GEMINI_PROPERTY = "invoice.localdemo.gemini-enabled";
    public static final String WORKER_ONLY_INVOICE_IDS_PROPERTY = "invoice.worker.only-invoice-ids";
    static final String OFFICIAL_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    /** POST /api/invoices/extract (legacy single-file extraction). Disabled in the local demo. */
    public static final String LEGACY_EXTRACT_PROPERTY = "invoice.legacy-extract.enabled";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        check(environment);
    }

    /** Static and side-effect free, so it can be unit-tested with any Environment. */
    public static void check(Environment env) {
        if (isIsolatedTestRun(env)) {
            return; // rule 1
        }
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
     * worker. INVOICE_LOCALDEMO_GEMINI_ENABLED=true is the explicit opt-in for a local extraction test; it still
     * needs the key, may only send it to Google's official endpoint, and a running worker must be limited to named
     * invoices (INVOICE_WORKER_ONLY_INVOICE_IDS) so the queued backlog is never processed by accident.
     * The database rules above apply unchanged, so even with the opt-in only the local demo database is used.
     */
    static void checkLocalDemoGemini(Environment env) {
        boolean hasKey = !env.getProperty("gemini.api-key", "").trim().isEmpty();
        boolean worker = "true".equalsIgnoreCase(env.getProperty("invoice.worker.enabled", "false").trim());
        if (!"true".equalsIgnoreCase(env.getProperty(LOCAL_DEMO_GEMINI_PROPERTY, "false").trim())) {
            if (hasKey) {
                throw refuse("the local demo must not have a Gemini API key; remove GEMINI_API_KEY from the launch "
                        + "configuration (or opt in deliberately with INVOICE_LOCALDEMO_GEMINI_ENABLED=true)");
            }
            if (worker) {
                throw refuse("the local demo must not enable the extraction worker (INVOICE_WORKER_ENABLED) unless "
                        + "INVOICE_LOCALDEMO_GEMINI_ENABLED=true");
            }
            return;
        }
        if (!hasKey) {
            throw refuse("INVOICE_LOCALDEMO_GEMINI_ENABLED=true but GEMINI_API_KEY is not set");
        }
        if (!OFFICIAL_GEMINI_BASE_URL.equals(env.getProperty("gemini.base-url", "").trim())) {
            throw refuse("with INVOICE_LOCALDEMO_GEMINI_ENABLED=true the Gemini key may only be sent to "
                    + OFFICIAL_GEMINI_BASE_URL + "; remove any gemini.base-url / GEMINI_BASE_URL override");
        }
        // The model is chosen on purpose, never the silent default: it must be the one checked in AI Studio.
        String model = env.getProperty("GEMINI_MODEL", "").trim();
        if (model.isEmpty()) {
            throw refuse("with INVOICE_LOCALDEMO_GEMINI_ENABLED=true set GEMINI_MODEL explicitly (the model whose limits "
                    + "you copied from Google AI Studio)");
        }
        if (!model.matches("[a-z0-9][a-z0-9.-]{1,79}") || !model.equals(env.getProperty("gemini.model", "").trim())) {
            throw refuse("GEMINI_MODEL must be a plain model id such as gemini-3.5-flash-lite, and nothing else may "
                    + "override gemini.model");
        }
        // Limits are validated at startup instead of leaving a silently idle worker. Messages name properties only.
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
            throw refuse("the Gemini limits are not usable: " + limits.getProblem() + " Use whole numbers from Google "
                    + "AI Studio (e.g. 250000, not 250k)");
        }
        // The legacy single-file endpoint would call Gemini for any file an admin uploads, outside the allowlist.
        if (!"false".equalsIgnoreCase(env.getProperty(LEGACY_EXTRACT_PROPERTY, "true").trim())) {
            throw refuse("with INVOICE_LOCALDEMO_GEMINI_ENABLED=true the legacy single-file extraction must stay "
                    + "disabled (" + LEGACY_EXTRACT_PROPERTY + "=false); only allowlisted queued invoices may be extracted");
        }
        if (worker) {
            String ids = env.getProperty(WORKER_ONLY_INVOICE_IDS_PROPERTY, "").trim();
            if (ids.isEmpty()) {
                throw refuse("the local demo worker must be limited to named invoices: set "
                        + "INVOICE_WORKER_ONLY_INVOICE_IDS (e.g. the id of one synthetic test invoice) so the existing "
                        + "queue is not processed");
            }
            if (!ids.matches("\\d+(\\s*,\\s*\\d+)*")) {
                throw refuse("INVOICE_WORKER_ONLY_INVOICE_IDS must be a comma-separated list of invoice ids");
            }
        }
    }

    /** Rule 3: no setting may let Hibernate or Spring change the schema. */
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

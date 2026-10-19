package com.actify.invoice.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
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
 *  1. The PostgreSQL test run (profile exactly "pgtest" AND the exact test database URL) is allowed, including
 *     ddl-auto=update. PgTestDatabaseGuard then double-checks current_database() before Hibernate runs.
 *  2. Every other start (Eclipse, mvnw spring-boot:run, java -jar) is refused unless the launch configuration
 *     sets INVOICE_LIVE_START_APPROVED=true AND INVOICE_APPROVED_DATABASE=host:port/database matching the JDBC URL.
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

    public static final String TEST_DATABASE = "Actify-Internship-Task-8-Invoice-LLM-Extraction_test_db";
    public static final String TEST_PROFILE = "pgtest";
    public static final String APPROVAL_PROPERTY = "invoice.live-start-approved";
    public static final String APPROVED_DATABASE_PROPERTY = "invoice.approved-database";

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
        if (!"true".equalsIgnoreCase(env.getProperty(APPROVAL_PROPERTY, "false").trim())) {
            throw refuse("starting against a non-test database is not approved. A database first needs a fresh, "
                    + "restore-tested backup and the reviewed schema script; then the approved launch configuration sets "
                    + "INVOICE_LIVE_START_APPROVED=true and INVOICE_APPROVED_DATABASE=host:port/database");
        }
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
        requireApprovedEndpoint(env);
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

    static boolean isIsolatedTestRun(Environment env) {
        if (!Arrays.equals(env.getActiveProfiles(), new String[]{TEST_PROFILE})) {
            return false;
        }
        String url = env.getProperty("spring.datasource.url", "");
        String withoutParams = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        return withoutParams.endsWith("/" + TEST_DATABASE);
    }

    /** Unset is fine (Spring Boot's default for a non-embedded database is to change nothing). */
    private static void requireOneOf(Environment env, String key, String... allowed) {
        String value = env.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!Arrays.asList(allowed).contains(normalized)) {
            throw refuse(key + "=" + normalized + " could change the database schema; allowed: "
                    + String.join(", ", allowed));
        }
    }

    private static IllegalStateException refuse(String reason) {
        return new IllegalStateException("SchemaSafetyGuard: REFUSING to start - " + reason + ".");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

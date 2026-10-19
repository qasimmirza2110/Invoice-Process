package com.actify.invoice.pgtest;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Fail-closed safety guard for the PostgreSQL integration tests.
 *
 * It runs as soon as the DataSource bean exists - BEFORE the JPA EntityManagerFactory is built, so before
 * Hibernate's ddl-auto=update can run any DDL - and stops the Spring context (and every test) unless:
 *   1. the active profiles are exactly ["pgtest"], and
 *   2. the configured JDBC URL names the test database, and
 *   3. the server itself answers "SELECT current_database()" with exactly the test database name.
 * The expected name is a constant here, not read from configuration, so a wrong property or an
 * environment variable (e.g. SPRING_DATASOURCE_URL) can never redirect the tests to the live database.
 */
public class PgTestDatabaseGuard implements BeanPostProcessor, EnvironmentAware, PriorityOrdered {

    public static final String TEST_DATABASE = "Actify-Internship-Task-8-Invoice-LLM-Extraction_test_db";
    public static final String REQUIRED_PROFILE = "pgtest";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource) {
            verify((DataSource) bean);
        }
        return bean;
    }

    void verify(DataSource dataSource) {
        String[] profiles = environment.getActiveProfiles();
        if (!Arrays.equals(profiles, new String[]{REQUIRED_PROFILE})) {
            throw refuse("active profiles are " + Arrays.toString(profiles) + ", expected exactly [" + REQUIRED_PROFILE + "]");
        }

        String url = environment.getProperty("spring.datasource.url", "");
        String urlWithoutParams = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (!urlWithoutParams.endsWith("/" + TEST_DATABASE)) {
            throw refuse("spring.datasource.url does not point at " + TEST_DATABASE);
        }

        String actual;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select current_database()")) {
            actual = rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("PgTestDatabaseGuard could not verify the database; refusing to run", e);
        }
        if (!TEST_DATABASE.equals(actual)) {
            throw refuse("connected database is '" + actual + "'");
        }
    }

    private IllegalStateException refuse(String reason) {
        return new IllegalStateException("PgTestDatabaseGuard: REFUSING to run PostgreSQL integration tests - "
                + reason + ". Only " + TEST_DATABASE + " may be used.");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @TestConfiguration
    public static class Config {
        // static: registered as a BeanPostProcessor before any DataSource/JPA bean is created.
        @Bean
        public static PgTestDatabaseGuard pgTestDatabaseGuard() {
            return new PgTestDatabaseGuard();
        }
    }
}

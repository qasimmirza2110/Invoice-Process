package com.actify.invoice.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
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

    private static final String LIVE_URL = "jdbc:postgresql://localhost:5432/Actify-Internship-Task-8-Invoice-LLM-Extraction_db";
    private static final String LIVE_PIN = "localhost:5432/Actify-Internship-Task-8-Invoice-LLM-Extraction_db";
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
                {"localhost:5433/Actify-Internship-Task-8-Invoice-LLM-Extraction_db", "only localhost:5433/"},
                {"db.example.test:5432/Actify-Internship-Task-8-Invoice-LLM-Extraction_db", "only db.example.test:5432/"},
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

    /** The real application.properties, exactly as an Eclipse start would see it (no approval variable set). */
    @Test
    void theMainConfigurationIsNonMutatingAndRefusedWithoutApproval() throws Exception {
        Properties main = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        assertThat(main.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(main.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(main.getProperty("invoice.live-start-approved")).isEqualTo("${INVOICE_LIVE_START_APPROVED:false}");

        List<String> pairs = new ArrayList<>();
        for (Map.Entry<Object, Object> e : main.entrySet()) {
            pairs.add(e.getKey() + "=" + e.getValue());
        }
        runner.withPropertyValues(pairs.toArray(new String[0])).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("not approved");
            assertThat(DATA_SOURCE_CREATED).isFalse();
        });
    }
}

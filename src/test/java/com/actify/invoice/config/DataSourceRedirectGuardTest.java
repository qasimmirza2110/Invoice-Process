package com.actify.invoice.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SchemaSafetyGuard approves ONE database by comparing INVOICE_APPROVED_DATABASE with spring.datasource.url. Spring Boot
 * binds spring.datasource.hikari.* onto the pool AFTER the URL, so e.g. SPRING_DATASOURCE_HIKARI_JDBCURL silently
 * replaces the approved URL. This test first proves that mechanism (no connection: Hikari connects on first use only),
 * then that the guard refuses every such redirect, also when it arrives as a Render-style environment variable.
 */
class DataSourceRedirectGuardTest {

    private static final String APPROVED_URL = "jdbc:postgresql://dpg-demo.example:5432/invoice_demo";
    private static final String OTHER_URL = "jdbc:postgresql://localhost:5432/" + SchemaSafetyGuard.LIVE_DATABASE;

    @Test
    void springBootReallyLetsTheHikariUrlReplaceTheApprovedOne() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=" + APPROVED_URL,
                        "spring.datasource.hikari.jdbc-url=" + OTHER_URL)
                .run(context -> {
                    DataSource dataSource = context.getBean(DataSource.class);
                    assertThat(dataSource).isInstanceOf(HikariDataSource.class);
                    assertThat(((HikariDataSource) dataSource).getJdbcUrl()).isEqualTo(OTHER_URL);
                });
    }

    /** An approved non-test start (render shape) with these extra OS environment variables. */
    private static StandardEnvironment approvedStartWith(Map<String, Object> extraVariables) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("SPRING_DATASOURCE_URL", APPROVED_URL);
        variables.put("INVOICE_LIVE_START_APPROVED", "true");
        variables.put("INVOICE_APPROVED_DATABASE", "dpg-demo.example:5432/invoice_demo");
        variables.put("SPRING_JPA_HIBERNATE_DDL_AUTO", "validate");
        variables.putAll(extraVariables);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        environment.setActiveProfiles("render");
        return environment;
    }

    private static Map<String, Object> one(String name, String value) {
        Map<String, Object> map = new HashMap<>();
        map.put(name, value);
        return map;
    }

    @Test
    void theApprovedStartWithoutRedirectsPasses() {
        assertThatCode(() -> SchemaSafetyGuard.check(approvedStartWith(new HashMap<>()))).doesNotThrowAnyException();
    }

    @Test
    void everyWayToRedirectTheConnectionIsRefused() {
        String[][] redirects = {
                {"SPRING_DATASOURCE_HIKARI_JDBCURL", OTHER_URL},
                {"SPRING_DATASOURCE_HIKARI_JDBC_URL", OTHER_URL},
                {"SPRING_DATASOURCE_HIKARI_DATASOURCECLASSNAME", "org.postgresql.ds.PGSimpleDataSource"},
                {"SPRING_DATASOURCE_HIKARI_DATASOURCEJNDI", "java:comp/env/jdbc/other"},
                {"SPRING_DATASOURCE_HIKARI_DATASOURCEPROPERTIES_SERVERNAME", "localhost"},
                {"SPRING_DATASOURCE_JNDINAME", "java:comp/env/jdbc/other"},
                {"SPRING_DATASOURCE_TYPE", "org.apache.tomcat.jdbc.pool.DataSource"},
        };
        for (String[] redirect : redirects) {
            assertThatThrownBy(() -> SchemaSafetyGuard.check(approvedStartWith(one(redirect[0], redirect[1]))))
                    .as(redirect[0])
                    .hasMessageContaining("REFUSING")
                    .hasMessageContaining("redirect")
                    .hasMessageNotContaining(redirect[1]);           // values are never echoed
        }
    }

    @Test
    void theExistingEndpointAndSchemaRulesStillCatchTheirOverrides() {
        assertThatThrownBy(() -> SchemaSafetyGuard.check(approvedStartWith(one("SPRING_DATASOURCE_URL", OTHER_URL))))
                .hasMessageContaining("only dpg-demo.example:5432/invoice_demo is approved");
        assertThatThrownBy(() -> SchemaSafetyGuard.check(approvedStartWith(one("SPRING_JPA_HIBERNATE_DDL_AUTO", "update"))))
                .hasMessageContaining("ddl-auto");
    }
}

package com.actify.invoice.pgtest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Proves the guard fails closed. No database: the DataSource is a Mockito mock. Runs in the normal test suite. */
class PgTestDatabaseGuardTest {

    private static final String TEST_URL = "jdbc:postgresql://localhost:5432/" + PgTestDatabaseGuard.TEST_DATABASE;
    private static final String LIVE_DB = "Actify-Internship-Task-8-Invoice-LLM-Extraction_db";

    private PgTestDatabaseGuard guard(String url, String... profiles) {
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.url", url);
        env.setActiveProfiles(profiles);
        PgTestDatabaseGuard guard = new PgTestDatabaseGuard();
        guard.setEnvironment(env);
        return guard;
    }

    private DataSource answering(String currentDatabase) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("select current_database()")).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(currentDatabase);
        return dataSource;
    }

    @Test
    void acceptsOnlyTheTestDatabaseWithThePgtestProfile() throws SQLException {
        assertThatCode(() -> guard(TEST_URL, "pgtest").verify(answering(PgTestDatabaseGuard.TEST_DATABASE)))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesWrongOrExtraProfilesWithoutEvenConnecting() throws SQLException {
        DataSource dataSource = answering(PgTestDatabaseGuard.TEST_DATABASE);
        assertThatThrownBy(() -> guard(TEST_URL).verify(dataSource)).hasMessageContaining("REFUSING");
        assertThatThrownBy(() -> guard(TEST_URL, "default").verify(dataSource)).hasMessageContaining("REFUSING");
        assertThatThrownBy(() -> guard(TEST_URL, "pgtest", "dev").verify(dataSource)).hasMessageContaining("REFUSING");
        verify(dataSource, never()).getConnection();
    }

    @Test
    void refusesTheLiveDatabaseUrl() throws SQLException {
        DataSource dataSource = answering(LIVE_DB);
        assertThatThrownBy(() -> guard("jdbc:postgresql://localhost:5432/" + LIVE_DB, "pgtest").verify(dataSource))
                .hasMessageContaining("REFUSING");
        verify(dataSource, never()).getConnection();
    }

    @Test
    void refusesWhenTheServerReportsAnyOtherDatabase() throws SQLException {
        // URL looks right, but the connection actually lands somewhere else (e.g. a proxy or an overridden URL).
        assertThatThrownBy(() -> guard(TEST_URL, "pgtest").verify(answering(LIVE_DB)))
                .hasMessageContaining("REFUSING").hasMessageContaining(LIVE_DB);
        assertThatThrownBy(() -> guard(TEST_URL, "pgtest").verify(answering(null)))
                .hasMessageContaining("REFUSING");
    }

    @Test
    void refusesWhenTheCheckItselfFails() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));
        assertThatThrownBy(() -> guard(TEST_URL, "pgtest").verify(dataSource)).hasMessageContaining("refusing to run");
    }

    @Test
    void urlParametersDoNotHideTheDatabaseName() throws SQLException {
        assertThatCode(() -> guard(TEST_URL + "?ApplicationName=x", "pgtest")
                .verify(answering(PgTestDatabaseGuard.TEST_DATABASE))).doesNotThrowAnyException();
        DataSource dataSource = answering(PgTestDatabaseGuard.TEST_DATABASE);
        assertThatThrownBy(() -> guard("jdbc:postgresql://localhost:5432/" + LIVE_DB + "?x=" + PgTestDatabaseGuard.TEST_DATABASE,
                "pgtest").verify(dataSource)).hasMessageContaining("REFUSING");
        verify(dataSource, never()).getConnection();
    }
}

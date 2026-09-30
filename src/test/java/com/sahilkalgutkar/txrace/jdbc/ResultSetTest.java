package com.sahilkalgutkar.txrace.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(H2Extension.class)
class ResultSetTest {

    private TracingDataSource traced;

    @BeforeEach
    void createTable(DataSource h2) throws SQLException {
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account (id INT AUTO_INCREMENT PRIMARY KEY, balance INT)");
            statement.execute("INSERT INTO account (balance) VALUES (100)");
        }
        traced = new TracingDataSource(h2);
    }

    @Test
    void resultSetsLeadBackToTheTracedStatement() throws SQLException {
        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT balance FROM account")) {
                assertThat(rows.getStatement()).isSameAs(statement);
            }
            statement.execute("SELECT balance FROM account");
            try (ResultSet rows = statement.getResultSet()) {
                assertThat(rows.getStatement()).isSameAs(statement);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(100);
            }
        }
    }

    @Test
    void generatedKeysLeadBackToTheTracedStatement() throws SQLException {
        try (Connection connection = traced.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO account (balance) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setInt(1, 5);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertThat(keys.getStatement()).isSameAs(insert);
                assertThat(keys.next()).isTrue();
                assertThat(keys.getInt(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void statementsReachedThroughAResultSetAreTraced() throws SQLException {
        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT balance FROM account")) {
                rows.getStatement().executeUpdate("UPDATE account SET balance = 5");
            }
        }

        assertThat(traced.trace().events()).extracting(e -> e.step().sql())
                .containsExactly("SELECT balance FROM account", "UPDATE account SET balance = 5");
    }

    @Test
    void metadataLeadsBackToTheTracedConnection() throws SQLException {
        try (Connection connection = traced.getConnection()) {
            assertThat(connection.getMetaData().getConnection()).isSameAs(connection);
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
            assertThat(connection.getMetaData().toString()).startsWith("txrace metadata over ");
            assertThat(connection.createStatement().executeQuery("SELECT 1").toString())
                    .startsWith("txrace result set over ");
        }
    }
}

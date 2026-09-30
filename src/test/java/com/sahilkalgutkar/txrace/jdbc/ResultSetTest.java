package com.sahilkalgutkar.txrace.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.trace.Step;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(H2Extension.class)
class ResultSetTest {

    private DataSource h2;
    private TracingDataSource traced;

    private int balance(int id) throws SQLException {
        try (Connection connection = h2.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT balance FROM account WHERE id = " + id)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @BeforeEach
    void createTable(DataSource h2) throws SQLException {
        this.h2 = h2;
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
    void updatingARowThroughTheResultSetIsAStep() throws SQLException {
        List<String> gated = new ArrayList<>();
        TracingDataSource gatedSource = new TracingDataSource(h2, new Gate() {
            @Override
            public void before(Step step) {
                gated.add(step.sql() == null ? step.kind().name() : step.sql());
            }
        });

        try (Connection connection = gatedSource.getConnection();
             Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
            connection.setAutoCommit(false);
            try (ResultSet rows = statement.executeQuery("SELECT id, balance FROM account WHERE id = 1")) {
                rows.next();
                rows.updateInt("balance", rows.getInt("balance") + 30);
                rows.updateRow();
            }
            connection.commit();
        }

        assertThat(gated).containsExactly(
                "SELECT id, balance FROM account WHERE id = 1", "ResultSet.updateRow()", "COMMIT");
        assertThat(gatedSource.trace().render()).contains(
                "c1 t1  ResultSet.updateRow()  [balance=130]  -> done");
        assertThat(balance(1)).isEqualTo(130);
    }

    @Test
    void insertingAndDeletingRowsAreSteps() throws SQLException {
        try (Connection connection = traced.getConnection();
             Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE)) {
            try (ResultSet rows = statement.executeQuery("SELECT id, balance FROM account")) {
                rows.moveToInsertRow();
                rows.updateInt(1, 7);
                rows.updateNull(2);
                rows.insertRow();
                rows.moveToCurrentRow();
                rows.first();
                rows.updateInt(2, 1);
                rows.deleteRow();
            }
        }

        assertThat(traced.trace().render()).contains(
                "ResultSet.insertRow()  [1=7, 2=NULL]  -> done",
                "ResultSet.deleteRow()  -> done");
    }

    @Test
    void updatesThatWereNeverAppliedAreForgotten() throws SQLException {
        try (Connection connection = traced.getConnection();
             Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
            try (ResultSet rows = statement.executeQuery("SELECT id, balance FROM account")) {
                rows.next();
                rows.updateInt(2, 1);
                rows.cancelRowUpdates();
                rows.updateInt(2, 2);
                rows.updateRow();
            }
        }

        assertThat(traced.trace().events().get(1).step().parameters()).hasSize(1);
        assertThat(balance(1)).isEqualTo(2);
    }

    @Test
    void updatesStayPendingWhenTheCursorMoves() throws SQLException {
        try (Connection connection = h2.getConnection()) {
            connection.createStatement().execute("INSERT INTO account (balance) VALUES (100)");
        }

        try (Connection connection = traced.getConnection();
             Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
            try (ResultSet rows = statement.executeQuery("SELECT id, balance FROM account ORDER BY id")) {
                rows.next();
                rows.updateInt(2, 50);
                rows.next();
                rows.updateRow();
            }
        }

        // The driver writes the update on the row the cursor reached, so the step has to carry it.
        assertThat(traced.trace().render()).contains("ResultSet.updateRow()  [2=50]");
        assertThat(balance(1)).isEqualTo(100);
        assertThat(balance(2)).isEqualTo(50);
    }

    @Test
    void theInsertRowKeepsItsOwnUpdates() throws SQLException {
        try (Connection connection = traced.getConnection();
             Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
            try (ResultSet rows = statement.executeQuery("SELECT id, balance FROM account")) {
                rows.next();
                rows.updateInt(2, 50);
                rows.moveToInsertRow();
                rows.updateInt(1, 3);
                rows.updateInt(2, 7);
                rows.insertRow();
                rows.moveToCurrentRow();
                rows.updateRow();
            }
        }

        assertThat(traced.trace().render()).contains(
                "ResultSet.insertRow()  [1=3, 2=7]", "ResultSet.updateRow()  [2=50]");
        assertThat(balance(1)).isEqualTo(50);
        assertThat(balance(3)).isEqualTo(7);
    }

    @Test
    void refreshingARowReadsTheDatabaseAsAStep() throws SQLException {
        try (Connection connection = traced.getConnection();
             Statement statement = connection.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE)) {
            connection.setAutoCommit(false);
            try (ResultSet rows = statement.executeQuery("SELECT id, balance FROM account")) {
                rows.next();
                rows.updateInt(2, 50);
                try (Connection other = h2.getConnection()) {
                    other.createStatement().executeUpdate("UPDATE account SET balance = 7");
                }
                rows.refreshRow();
                assertThat(rows.getInt(2)).isEqualTo(7);
                rows.updateRow();
            }
            connection.commit();
        }

        assertThat(traced.trace().render()).contains(
                "ResultSet.refreshRow()  -> rows", "ResultSet.updateRow()  -> done");
        assertThat(balance(1)).isEqualTo(7);
    }

    @Test
    void theSameResultSetComesBackAsTheSameProxy() throws SQLException {
        try (Connection connection = traced.getConnection();
             Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
            ResultSet rows = statement.executeQuery("SELECT id, balance FROM account");
            assertThat(statement.getResultSet()).isSameAs(rows);

            rows.next();
            rows.updateInt(2, 55);
            statement.getResultSet().updateRow();
        }

        assertThat(traced.trace().render()).contains("ResultSet.updateRow()  [2=55]");
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

package com.sahilkalgutkar.txrace.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahilkalgutkar.txrace.trace.Outcome;
import com.sahilkalgutkar.txrace.trace.Step;
import com.sahilkalgutkar.txrace.trace.Trace;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbc.JdbcConnection;
import org.h2.jdbc.JdbcPreparedStatement;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(H2Extension.class)
class TracingDataSourceTest {

    private static void createAccounts(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account (id INT PRIMARY KEY, owner VARCHAR(40), balance INT)");
            statement.execute("INSERT INTO account VALUES (1, 'ana', 100), (2, 'ben', 50)");
        }
    }

    private static List<Outcome> outcomes(Trace trace) {
        return trace.events().stream().map(Trace.Event::outcome).toList();
    }

    @Test
    void recordsPlainStatementsWithWhatTheyReturned(DataSource h2) throws SQLException {
        createAccounts(h2);
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE account SET balance = balance + 1");
            try (ResultSet rows = statement.executeQuery("SELECT balance FROM account")) {
                assertThat(rows.next()).isTrue();
            }
            statement.executeLargeUpdate("DELETE FROM account WHERE id = 2");
        }

        assertThat(traced.trace().events()).extracting(e -> e.step().sql()).containsExactly(
                "UPDATE account SET balance = balance + 1",
                "SELECT balance FROM account",
                "DELETE FROM account WHERE id = 2");
        assertThat(outcomes(traced.trace())).containsExactly(
                new Outcome.Updated(2), new Outcome.Rows(), new Outcome.Updated(1));
    }

    @Test
    void describesExecuteByWhatItProduced(DataSource h2) throws SQLException {
        createAccounts(h2);
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT * FROM account");
            statement.execute("UPDATE account SET owner = UPPER(owner)");
            statement.execute("SET SCHEMA PUBLIC");
        }

        assertThat(outcomes(traced.trace())).containsExactly(
                new Outcome.Rows(), new Outcome.Updated(2), new Outcome.Updated(0));
    }

    @Test
    void aStatementThatRanIsNeverReportedAsFailing(DataSource h2) throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2);

        // SHUTDOWN succeeds, but asking for its update count afterwards fails because the
        // database is gone. The caller should see what the driver itself returned.
        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            assertThat(statement.execute("SHUTDOWN")).isFalse();
        }

        assertThat(outcomes(traced.trace())).containsExactly(new Outcome.Done());
    }

    @Test
    void recordsBoundParametersInPlaceholderOrder(DataSource h2) throws SQLException {
        createAccounts(h2);
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection connection = traced.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE account SET owner = ?, balance = ? WHERE id = ?")) {
            update.setInt(3, 1);
            update.setNull(1, Types.VARCHAR);
            update.setObject(2, 75, Types.INTEGER);
            update.executeUpdate();
        }

        Step step = traced.trace().events().getFirst().step();
        assertThat(step.sql()).isEqualTo("UPDATE account SET owner = ?, balance = ? WHERE id = ?");
        assertThat(step.parameters()).containsExactly(null, 75, 1);
        assertThat(traced.trace().events().getFirst().outcome()).isEqualTo(new Outcome.Updated(1));
    }

    @Test
    void keepsParametersAcrossExecutionsUntilCleared(DataSource h2) throws SQLException {
        createAccounts(h2);
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection connection = traced.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT balance FROM account WHERE id = ?")) {
            select.setInt(1, 1);
            select.executeQuery().close();
            select.execute();
            select.clearParameters();
            select.setInt(1, 2);
            select.executeQuery().close();
        }

        assertThat(traced.trace().events()).extracting(e -> e.step().parameters())
                .containsExactly(List.of(1), List.of(1), List.of(2));
    }

    @Test
    void recordsAFailureAndStillThrowsIt(DataSource h2) throws SQLException {
        createAccounts(h2);
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO account VALUES (1, 'dup', 0)"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("23505");
        }

        Outcome outcome = traced.trace().events().getFirst().outcome();
        assertThat(outcome).isInstanceOfSatisfying(Outcome.Failed.class,
                failed -> assertThat(failed.sqlState()).isEqualTo("23505"));
    }

    @Test
    void numbersConnectionsInTheOrderTheyWereOpened(DataSource h2) throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection first = traced.getConnection(); Connection second = traced.getConnection()) {
            second.createStatement().execute("SELECT 2");
            first.createStatement().execute("SELECT 1");
        }

        assertThat(traced.trace().events()).extracting(e -> e.step().connection()).containsExactly(2, 1);
        assertThat(traced.trace().of(1)).extracting(e -> e.step().sql()).containsExactly("SELECT 1");
    }

    @Test
    void theGateSeesEachStepBeforeAndAfterTheDriver(DataSource h2) throws SQLException {
        List<String> seen = new ArrayList<>();
        Gate gate = new Gate() {
            @Override
            public void before(Step step) {
                seen.add("before " + step.sql());
            }

            @Override
            public void after(Step step, Outcome outcome) {
                seen.add("after " + outcome);
            }
        };
        TracingDataSource traced = new TracingDataSource(h2, gate);

        try (Connection connection = traced.getConnection()) {
            connection.createStatement().executeQuery("SELECT 1").close();
        }

        assertThat(seen).containsExactly("before SELECT 1", "after Rows[]");
    }

    @Test
    void aStepTheGateRefusesNeverReachesTheDriver(DataSource h2) throws SQLException {
        createAccounts(h2);
        Gate refuse = new Gate() {
            @Override
            public void before(Step step) throws SQLException {
                throw new SQLException("not your turn");
            }
        };
        TracingDataSource traced = new TracingDataSource(h2, refuse);

        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM account"))
                    .hasMessage("not your turn");
        }

        assertThat(traced.trace().events()).isEmpty();
        try (Connection connection = h2.getConnection();
             ResultSet count = connection.createStatement().executeQuery("SELECT COUNT(*) FROM account")) {
            count.next();
            assertThat(count.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void statementsHandBackTheTracedConnection(DataSource h2) throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection connection = traced.getConnection()) {
            assertThat(connection.createStatement().getConnection()).isSameAs(connection);
            assertThat(connection.prepareStatement("SELECT 1").getConnection()).isSameAs(connection);
            assertThat(connection.prepareCall("SELECT 1").getConnection()).isSameAs(connection);
        }
    }

    @Test
    void unwrapsToTheProxyForJdbcInterfacesAndToTheDriverOtherwise(DataSource h2) throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection connection = traced.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
            assertThat(connection.unwrap(JdbcConnection.class)).isInstanceOf(JdbcConnection.class);
            assertThat(connection.isWrapperFor(JdbcConnection.class)).isTrue();
            assertThat(connection.isWrapperFor(Connection.class)).isTrue();
            assertThat(statement.unwrap(PreparedStatement.class)).isSameAs(statement);
            assertThat(statement.unwrap(JdbcPreparedStatement.class)).isInstanceOf(JdbcPreparedStatement.class);
        }
    }

    @Test
    void proxiesAreEqualOnlyToThemselves(DataSource h2) throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection first = traced.getConnection(); Connection second = traced.getConnection()) {
            assertThat(first).isEqualTo(first).isNotEqualTo(second);
            assertThat(first.hashCode()).isEqualTo(System.identityHashCode(first));
            assertThat(first.toString()).startsWith("txrace connection 1 over ");
            assertThat(first.createStatement().toString()).startsWith("txrace statement over ");
        }
    }

    @Test
    void passesTheRestOfTheDataSourceThrough(DataSource h2) throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2);

        traced.setLoginTimeout(7);
        traced.setLogWriter(null);

        assertThat(traced.getLoginTimeout()).isEqualTo(h2.getLoginTimeout()).isEqualTo(7);
        assertThat(traced.getLogWriter()).isNull();
        assertThat(traced.getParentLogger()).isSameAs(h2.getParentLogger());
        assertThat(traced.unwrap(TracingDataSource.class)).isSameAs(traced);
        assertThat(traced.unwrap(JdbcDataSource.class)).isSameAs(h2);
        assertThat(traced.isWrapperFor(DataSource.class)).isTrue();
        assertThat(traced.isWrapperFor(JdbcDataSource.class)).isTrue();
        try (Connection connection = traced.getConnection("sa", "")) {
            assertThat(connection.isValid(1)).isTrue();
        }
    }
}

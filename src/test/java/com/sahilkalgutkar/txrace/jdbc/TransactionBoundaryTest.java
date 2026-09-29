package com.sahilkalgutkar.txrace.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.trace.Outcome;
import com.sahilkalgutkar.txrace.trace.Step;
import com.sahilkalgutkar.txrace.trace.Trace;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(H2Extension.class)
class TransactionBoundaryTest {

    private DataSource h2;

    @BeforeEach
    void createTable(DataSource h2) throws SQLException {
        this.h2 = h2;
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE item (id INT PRIMARY KEY)");
        }
    }

    private int count() throws SQLException {
        try (Connection connection = h2.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM item")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String summary(Trace trace) {
        StringBuilder out = new StringBuilder();
        for (Trace.Event event : trace.events()) {
            Step step = event.step();
            out.append('t').append(step.transaction()).append(' ')
                    .append(step.endsTransaction() ? step.kind() : step.sql()).append('\n');
        }
        return out.toString();
    }

    @Test
    void commitAndRollbackEachEndATransaction() throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO item VALUES (1)");
            connection.commit();
            statement.executeUpdate("INSERT INTO item VALUES (2)");
            connection.rollback();
            statement.executeQuery("SELECT * FROM item").close();
        }

        assertThat(summary(traced.trace())).isEqualTo("""
                t1 INSERT INTO item VALUES (1)
                t1 COMMIT
                t2 INSERT INTO item VALUES (2)
                t2 ROLLBACK
                t3 SELECT * FROM item
                """);
        assertThat(traced.trace().events().get(1).outcome()).isEqualTo(new Outcome.Done());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void withAutocommitEveryStatementIsItsOwnTransaction() throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO item VALUES (1)");
            try {
                statement.executeUpdate("INSERT INTO item VALUES (1)");
            } catch (SQLException expected) {
                // A failed statement is still a transaction of its own.
            }
            statement.executeUpdate("INSERT INTO item VALUES (2)");
        }

        assertThat(traced.trace().events()).extracting(e -> e.step().transaction()).containsExactly(1, 2, 3);
    }

    @Test
    void turningAutocommitOnPartWayThroughCommits() throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO item VALUES (1)");
            connection.setAutoCommit(true);
            assertThat(count()).isEqualTo(1);
            connection.setAutoCommit(true);
            statement.executeUpdate("INSERT INTO item VALUES (2)");
        }

        assertThat(summary(traced.trace())).isEqualTo("""
                t1 INSERT INTO item VALUES (1)
                t1 COMMIT
                t2 INSERT INTO item VALUES (2)
                """);
    }

    @Test
    void rollingBackToASavepointIsNotAStep() throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2);

        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO item VALUES (1)");
            Savepoint savepoint = connection.setSavepoint();
            statement.executeUpdate("INSERT INTO item VALUES (2)");
            connection.rollback(savepoint);
            connection.commit();
        }

        assertThat(summary(traced.trace())).isEqualTo("""
                t1 INSERT INTO item VALUES (1)
                t1 INSERT INTO item VALUES (2)
                t1 COMMIT
                """);
        assertThat(count()).isEqualTo(1);
    }
}

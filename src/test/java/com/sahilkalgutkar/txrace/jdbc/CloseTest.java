package com.sahilkalgutkar.txrace.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahilkalgutkar.txrace.trace.Step;
import java.sql.Connection;
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
class CloseTest {

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

    @Test
    void closingWithWorkPendingRollsItBackThroughTheGate() throws SQLException {
        List<Step.Kind> seen = new ArrayList<>();
        TracingDataSource traced = new TracingDataSource(h2, new Gate() {
            @Override
            public void before(Step step) {
                seen.add(step.kind());
            }
        });

        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO item VALUES (1)");
        }

        assertThat(seen).containsExactly(Step.Kind.STATEMENT, Step.Kind.ROLLBACK);
        assertThat(traced.trace().of(1)).extracting(e -> e.step().transaction()).containsExactly(1, 1);
        assertThat(count()).isZero();
    }

    @Test
    void closingWithNothingPendingAddsNoStep() throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2);

        Connection connection = traced.getConnection();
        connection.setAutoCommit(false);
        connection.createStatement().executeUpdate("INSERT INTO item VALUES (1)");
        connection.commit();
        connection.close();
        connection.close();

        assertThat(traced.trace().events()).extracting(e -> e.step().kind())
                .containsExactly(Step.Kind.STATEMENT, Step.Kind.COMMIT);
        assertThat(connection.isClosed()).isTrue();
    }

    @Test
    void theConnectionStillClosesWhenTheGateRefusesTheRollback() throws SQLException {
        TracingDataSource traced = new TracingDataSource(h2, new Gate() {
            @Override
            public void before(Step step) throws SQLException {
                if (step.kind() == Step.Kind.ROLLBACK) {
                    throw new SQLException("not your turn");
                }
            }
        });

        Connection connection = traced.getConnection();
        connection.setAutoCommit(false);
        connection.createStatement().executeUpdate("INSERT INTO item VALUES (1)");

        assertThatThrownBy(connection::close).hasMessage("not your turn");
        assertThat(connection.isClosed()).isTrue();
        assertThat(count()).isZero();
    }
}

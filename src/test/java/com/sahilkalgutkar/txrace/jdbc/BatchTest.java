package com.sahilkalgutkar.txrace.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahilkalgutkar.txrace.trace.Outcome;
import com.sahilkalgutkar.txrace.trace.Step;
import com.sahilkalgutkar.txrace.trace.Trace;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(H2Extension.class)
class BatchTest {

    private TracingDataSource traced;

    @BeforeEach
    void createTable(DataSource h2) throws SQLException {
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE item (id INT PRIMARY KEY, name VARCHAR(20))");
        }
        traced = new TracingDataSource(h2);
    }

    @Test
    void aPreparedBatchRecordsEachRow() throws SQLException {
        try (Connection connection = traced.getConnection();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO item VALUES (?, ?)")) {
            insert.setInt(1, 1);
            insert.setString(2, "a");
            insert.addBatch();
            insert.setInt(1, 2);
            insert.setString(2, "b");
            insert.addBatch();
            insert.executeBatch();
        }

        Trace.Event event = traced.trace().events().getFirst();
        assertThat(event.step().kind()).isEqualTo(Step.Kind.BATCH);
        assertThat(event.step().sql()).isEqualTo("INSERT INTO item VALUES (?, ?)");
        assertThat(event.step().parameters()).containsExactly(List.of(1, "a"), List.of(2, "b"));
        assertThat(event.outcome()).isEqualTo(new Outcome.Batch(List.of(1L, 1L)));
    }

    @Test
    void aPlainBatchJoinsItsSql() throws SQLException {
        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            statement.addBatch("INSERT INTO item VALUES (1, 'a')");
            statement.addBatch("UPDATE item SET name = 'z'");
            statement.executeLargeBatch();
        }

        Trace.Event event = traced.trace().events().getFirst();
        assertThat(event.step().sql()).isEqualTo("INSERT INTO item VALUES (1, 'a'); UPDATE item SET name = 'z'");
        assertThat(event.step().parameters()).isEmpty();
        assertThat(event.outcome()).isEqualTo(new Outcome.Batch(List.of(1L, 1L)));
    }

    @Test
    void eachRunStartsFromAnEmptyBatch() throws SQLException {
        try (Connection connection = traced.getConnection();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO item VALUES (?, 'x')")) {
            insert.setInt(1, 1);
            insert.addBatch();
            insert.executeBatch();
            insert.setInt(1, 2);
            insert.addBatch();
            insert.clearBatch();
            insert.setInt(1, 3);
            insert.addBatch();
            insert.executeBatch();
        }

        assertThat(traced.trace().events()).extracting(e -> e.step().parameters())
                .containsExactly(List.of(List.of(1)), List.of(List.of(3)));
    }

    @Test
    void aFailedBatchIsRecordedAndForgotten() throws SQLException {
        try (Connection connection = traced.getConnection(); Statement statement = connection.createStatement()) {
            statement.addBatch("INSERT INTO item VALUES (1, 'a')");
            statement.addBatch("INSERT INTO item VALUES (1, 'b')");
            assertThatThrownBy(statement::executeBatch).isInstanceOf(BatchUpdateException.class);

            statement.addBatch("INSERT INTO item VALUES (2, 'c')");
            statement.executeBatch();
        }

        List<Trace.Event> events = traced.trace().events();
        assertThat(events.get(0).outcome()).isInstanceOfSatisfying(Outcome.Failed.class,
                failed -> assertThat(failed.sqlState()).isEqualTo("23505"));
        assertThat(events.get(1).step().sql()).isEqualTo("INSERT INTO item VALUES (2, 'c')");
    }

    @Test
    void aPreparedStatementRefusesPlainSqlInItsBatch() throws SQLException {
        try (Connection connection = traced.getConnection();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO item VALUES (?, 'x')")) {
            assertThatThrownBy(() -> insert.addBatch("DELETE FROM item")).isInstanceOf(SQLException.class);
            insert.setInt(1, 1);
            insert.addBatch();
            insert.executeBatch();
        }

        assertThat(traced.trace().events().getFirst().step().parameters()).containsExactly(List.of(1));
    }
}

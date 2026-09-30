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

/**
 * Two deposits into one account, interleaved by hand on one thread. This is the order the
 * scheduler will find on its own later; here it is written out step by step.
 */
@ExtendWith(H2Extension.class)
class LostUpdateTest {

    private DataSource h2;
    private TracingDataSource traced;

    @BeforeEach
    void openAccount(DataSource h2) throws SQLException {
        this.h2 = h2;
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL)");
            statement.execute("INSERT INTO account VALUES (1, 100)");
        }
        traced = new TracingDataSource(h2);
    }

    private static Connection begin(DataSource source) throws SQLException {
        Connection connection = source.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static int read(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT balance FROM account WHERE id = ?")) {
            select.setInt(1, 1);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static void write(Connection connection, int balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE account SET balance = ? WHERE id = ?")) {
            update.setInt(1, balance);
            update.setInt(2, 1);
            update.executeUpdate();
        }
    }

    private int balance() throws SQLException {
        try (Connection connection = h2.getConnection()) {
            return read(connection);
        }
    }

    @Test
    void readThenWriteLosesTheFirstDeposit() throws SQLException {
        try (Connection first = begin(traced); Connection second = begin(traced)) {
            int seenByFirst = read(first);
            int seenBySecond = read(second);
            write(first, seenByFirst + 30);
            first.commit();
            write(second, seenBySecond + 50);
            second.commit();
        }

        // Either serial order ends at 180.
        assertThat(balance()).isEqualTo(150);
        assertThat(traced.trace().render()).isEqualTo("""
                  1  c1 t1  SELECT balance FROM account WHERE id = ?  [1]  -> rows
                  2  c2 t1  SELECT balance FROM account WHERE id = ?  [1]  -> rows
                  3  c1 t1  UPDATE account SET balance = ? WHERE id = ?  [130, 1]  -> updated 1
                  4  c1 t1  COMMIT  -> done
                  5  c2 t1  UPDATE account SET balance = ? WHERE id = ?  [150, 1]  -> updated 1
                  6  c2 t1  COMMIT  -> done
                """);
    }

    @Test
    void anAtomicIncrementSurvivesTheSameOrder() throws SQLException {
        try (Connection first = begin(traced); Connection second = begin(traced)) {
            read(first);
            read(second);
            increment(first, 30);
            first.commit();
            increment(second, 50);
            second.commit();
        }

        assertThat(balance()).isEqualTo(180);
    }

    private static void increment(Connection connection, int amount) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE account SET balance = balance + ? WHERE id = ?")) {
            update.setInt(1, amount);
            update.setInt(2, 1);
            update.executeUpdate();
        }
    }
}

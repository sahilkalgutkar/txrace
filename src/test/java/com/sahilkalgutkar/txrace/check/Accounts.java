package com.sahilkalgutkar.txrace.check;

import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/** One account with 100 in it, and ways to put money in. */
final class Accounts {

    private Accounts() {
    }

    static void open(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM account");
            statement.execute("INSERT INTO account VALUES (1, 100)");
        }
    }

    static Object balance(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT balance FROM account WHERE id = 1")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    static Object read(Connection connection) throws SQLException {
        try (ResultSet rows = connection.createStatement().executeQuery("SELECT balance FROM account WHERE id = 1")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** Reads the balance, then writes back the balance plus {@code amount}, and returns what it wrote. */
    static Transaction deposit(int amount) {
        return connection -> {
            int balance;
            try (ResultSet rows = connection.createStatement()
                    .executeQuery("SELECT balance FROM account WHERE id = 1")) {
                rows.next();
                balance = rows.getInt(1) + amount;
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE account SET balance = ? WHERE id = 1")) {
                update.setInt(1, balance);
                update.executeUpdate();
            }
            return balance;
        };
    }

    /** Adds {@code amount} in one statement. */
    static Transaction increment(int amount) {
        return connection -> connection.createStatement()
                .executeUpdate("UPDATE account SET balance = balance + " + amount + " WHERE id = 1");
    }

    /** Runs {@code transaction} at the given isolation level. */
    static Transaction at(int isolation, Transaction transaction) {
        return connection -> {
            connection.setTransactionIsolation(isolation);
            return transaction.run(connection);
        };
    }
}

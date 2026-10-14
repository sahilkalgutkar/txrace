package com.sahilkalgutkar.txrace.zoo;

import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;

/**
 * Two payments of 60 from a wallet holding 100. Each checks the balance, finds enough, and takes
 * the money with a relative update, so neither payment is lost the way an absolute write would
 * lose it. But the check was made against a balance the other payment was about to change, and
 * both go through.
 */
final class Overdraft implements Specimen {

    @Override
    public void create(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE wallet (id INT PRIMARY KEY, balance INT NOT NULL)");
    }

    static void fill(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM wallet");
            statement.execute("INSERT INTO wallet VALUES (1, 100)");
        }
    }

    @Override
    public Object observe(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT balance FROM wallet WHERE id = 1")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** Checks the balance, then takes the money off it. */
    static Transaction checkThenPay(int amount) {
        return connection -> {
            try (ResultSet rows = connection.createStatement()
                    .executeQuery("SELECT balance FROM wallet WHERE id = 1")) {
                rows.next();
                if (rows.getInt(1) < amount) {
                    return "declined";
                }
            }
            try (PreparedStatement pay = connection.prepareStatement(
                    "UPDATE wallet SET balance = balance - ? WHERE id = 1")) {
                pay.setInt(1, amount);
                pay.executeUpdate();
            }
            return "paid";
        };
    }

    /**
     * The check and the payment in one statement. A second payment waits for the first one's row
     * lock, and once it commits, the database checks the balance again on the new row.
     */
    static Transaction payIfEnough(int amount) {
        return connection -> {
            try (PreparedStatement pay = connection.prepareStatement(
                    "UPDATE wallet SET balance = balance - ? WHERE id = 1 AND balance >= ?")) {
                pay.setInt(1, amount);
                pay.setInt(2, amount);
                return pay.executeUpdate() == 1 ? "paid" : "declined";
            }
        };
    }

    @Override
    public Twin buggy() {
        return new Twin(Overdraft::fill, List.of(checkThenPay(60), checkThenPay(60)));
    }

    @Override
    public Twin fixed() {
        return new Twin(Overdraft::fill, List.of(payIfEnough(60), payIfEnough(60)));
    }
}

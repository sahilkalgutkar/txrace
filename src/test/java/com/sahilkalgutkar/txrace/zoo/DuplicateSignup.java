package com.sahilkalgutkar.txrace.zoo;

import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Two people signing up with the same email at once. Each looks for the address, finds nothing,
 * and inserts it. Without a constraint nothing stops the second insert, so the address ends up
 * with two accounts. The fix is the constraint, with the insert asking the database what happened
 * instead of the code asking first.
 */
final class DuplicateSignup implements Specimen {

    private static final String EMAIL = "ada@example.com";

    @Override
    public void create(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE signup (email TEXT NOT NULL, name TEXT NOT NULL)");
    }

    static void withoutConstraint(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS signup_email");
            statement.execute("DELETE FROM signup");
        }
    }

    static void withConstraint(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM signup");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS signup_email ON signup (email)");
        }
    }

    /** Every account as "email name", so a run shows who got the address. */
    @Override
    public Object observe(DataSource database) throws SQLException {
        List<String> accounts = new ArrayList<>();
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement()
                     .executeQuery("SELECT email, name FROM signup ORDER BY name")) {
            while (rows.next()) {
                accounts.add(rows.getString(1) + " " + rows.getString(2));
            }
        }
        return accounts;
    }

    static Transaction lookThenInsert(String name) {
        return connection -> {
            try (PreparedStatement look = connection.prepareStatement("SELECT 1 FROM signup WHERE email = ?")) {
                look.setString(1, EMAIL);
                try (ResultSet rows = look.executeQuery()) {
                    if (rows.next()) {
                        return "taken";
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO signup VALUES (?, ?)")) {
                insert.setString(1, EMAIL);
                insert.setString(2, name);
                insert.executeUpdate();
            }
            return "registered";
        };
    }

    /**
     * A second insert of the same address waits for the first one to commit or roll back, then
     * either does nothing or goes ahead, and says which.
     */
    static Transaction insertUnlessTaken(String name) {
        return connection -> {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO signup VALUES (?, ?) ON CONFLICT (email) DO NOTHING")) {
                insert.setString(1, EMAIL);
                insert.setString(2, name);
                return insert.executeUpdate() == 1 ? "registered" : "taken";
            }
        };
    }

    @Override
    public Twin buggy() {
        return new Twin(DuplicateSignup::withoutConstraint, List.of(lookThenInsert("ada"), lookThenInsert("eve")));
    }

    @Override
    public Twin fixed() {
        return new Twin(DuplicateSignup::withConstraint, List.of(insertUnlessTaken("ada"), insertUnlessTaken("eve")));
    }
}

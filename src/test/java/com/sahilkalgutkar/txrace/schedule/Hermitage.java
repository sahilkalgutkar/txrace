package com.sahilkalgutkar.txrace.schedule;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Martin Kleppmann's Hermitage tests (github.com/ept/hermitage) as txrace scenarios: the same
 * table, the same transactions, the same interleaving, and a check for whether the anomaly
 * happened. The interleavings are the ones in Hermitage's postgres.md. The one change is the
 * column name: Hermitage's {@code value} is a reserved word in H2, so here it is {@code val}.
 */
final class Hermitage {

    private Hermitage() {
    }

    /** Whether the anomaly showed in a run, from what the transactions saw or what was left behind. */
    @FunctionalInterface
    interface Detector {
        boolean occurred(Run run, DataSource database) throws SQLException;
    }

    record Scenario(String anomaly, String name, String order, List<Transaction> transactions, Detector detector) {
    }

    /** One statement of a transaction. Reads return what they saw, writes return {@link #NOTHING}. */
    @FunctionalInterface
    interface Sql {
        Object run(Connection connection) throws SQLException;
    }

    private static final Object NOTHING = new Object();

    /** Hermitage's "abort": the transaction gives up of its own accord. */
    static final class Abort extends RuntimeException {
        Abort() {
            super("abort", null, false, false);
        }
    }

    static void createTable(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE test (id INT PRIMARY KEY, val INT)");
        }
    }

    static void setUp(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM test");
            statement.execute("INSERT INTO test (id, val) VALUES (1, 10), (2, 20)");
        }
    }

    /** Runs {@code steps} in order and returns everything the reads among them saw. */
    static Transaction transaction(Sql... steps) {
        return connection -> {
            List<Object> seen = new ArrayList<>();
            for (Sql step : steps) {
                Object value = step.run(connection);
                if (value != NOTHING) {
                    seen.add(value);
                }
            }
            return seen;
        };
    }

    static Sql update(int id, int value) {
        return connection -> {
            connection.createStatement().executeUpdate("UPDATE test SET val = " + value + " WHERE id = " + id);
            return NOTHING;
        };
    }

    static Sql insert(int id, int value) {
        return connection -> {
            connection.createStatement().executeUpdate("INSERT INTO test (id, val) VALUES (" + id + ", " + value + ")");
            return NOTHING;
        };
    }

    /** The value of row {@code id}, or null if there is none. */
    static Sql read(int id) {
        return connection -> {
            try (ResultSet rows = connection.createStatement().executeQuery("SELECT val FROM test WHERE id = " + id)) {
                return rows.next() ? rows.getInt(1) : null;
            }
        };
    }

    /** How many rows match {@code predicate}. */
    static Sql count(String predicate) {
        return connection -> {
            try (ResultSet rows = connection.createStatement()
                    .executeQuery("SELECT COUNT(*) FROM test WHERE " + predicate)) {
                rows.next();
                return rows.getInt(1);
            }
        };
    }

    static Sql abort() {
        return connection -> {
            throw new Abort();
        };
    }

    /** What transaction n saw, or null if it did not commit. */
    @SuppressWarnings("unchecked")
    static List<Object> saw(Run run, int n) {
        return run.result(n) instanceof Result.Committed committed ? (List<Object>) committed.value() : null;
    }

    static boolean committed(Run run, int n) {
        return run.result(n) instanceof Result.Committed;
    }

    static int value(DataSource database, int id) throws SQLException {
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT val FROM test WHERE id = " + id)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    static final List<Scenario> ALL = List.of(
            new Scenario("G0", "write cycles", "1 2 1 1 2 2",
                    List.of(transaction(update(1, 11), update(2, 21)), transaction(update(1, 12), update(2, 22))),
                    // Both rows have to end up written by the same transaction.
                    (run, database) -> value(database, 1) - 10 != value(database, 2) - 20),
            new Scenario("G1a", "aborted reads", "1 2 1 2 2",
                    List.of(transaction(update(1, 101), abort()), transaction(read(1), read(1))),
                    (run, database) -> saw(run, 2) != null && saw(run, 2).contains(101)),
            new Scenario("G1b", "intermediate reads", "1 2 1 1 2 2",
                    List.of(transaction(update(1, 101), update(1, 11)), transaction(read(1), read(1))),
                    (run, database) -> saw(run, 2) != null && saw(run, 2).contains(101)),
            new Scenario("G1c", "circular information flow", "1 2 1 2 1 2",
                    List.of(transaction(update(1, 11), read(2)), transaction(update(2, 22), read(1))),
                    // Each saw the other's uncommitted write.
                    (run, database) -> saw(run, 1) != null && saw(run, 2) != null
                            && Objects.equals(saw(run, 1).getFirst(), 22)
                            && Objects.equals(saw(run, 2).getFirst(), 11)),
            new Scenario("OTV", "observed transaction vanishes", "1 1 2 1 3 2 3 2 3 3 3",
                    List.of(transaction(update(1, 11), update(2, 19)), transaction(update(1, 12), update(2, 18)),
                            transaction(read(1), read(2), read(2), read(1))),
                    // Having seen 2's write to row 2, the third reads row 1 and finds 1's value instead.
                    (run, database) -> saw(run, 3) != null
                            && Objects.equals(saw(run, 3).get(2), 18) && Objects.equals(saw(run, 3).get(3), 11)),
            new Scenario("PMP", "predicate-many-preceders", "1 2 2 1 1",
                    List.of(transaction(count("val = 30"), count("val % 3 = 0")), transaction(insert(3, 30))),
                    // The first predicate found nothing, the second finds the row inserted in between.
                    (run, database) -> saw(run, 1) != null && saw(run, 1).equals(List.of(0, 1))),
            new Scenario("P4", "lost update", "1 2 1 2 1 2",
                    List.of(transaction(read(1), update(1, 11)), transaction(read(1), update(1, 11))),
                    (run, database) -> committed(run, 1) && committed(run, 2)),
            new Scenario("G-single", "read skew", "1 2 2 2 2 2 1 1",
                    List.of(transaction(read(1), read(2)),
                            transaction(read(1), read(2), update(1, 12), update(2, 18))),
                    // The first saw row 1 before the second changed it and row 2 after.
                    (run, database) -> saw(run, 1) != null && saw(run, 1).equals(List.of(10, 18))),
            new Scenario("G2-item", "write skew", "1 2 1 2 1 2",
                    List.of(transaction(count("id IN (1, 2)"), update(1, 11)),
                            transaction(count("id IN (1, 2)"), update(2, 21))),
                    (run, database) -> committed(run, 1) && committed(run, 2)),
            new Scenario("G2", "anti-dependency cycles", "1 2 1 2 1 2",
                    List.of(transaction(count("val % 3 = 0"), insert(3, 30)),
                            transaction(count("val % 3 = 0"), insert(4, 42))),
                    (run, database) -> committed(run, 1) && committed(run, 2)));
}

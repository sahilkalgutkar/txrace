package com.sahilkalgutkar.txrace.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a lock watch has to report, whichever database it asks. */
abstract class LockWatchContract {

    private DataSource database;

    @BeforeEach
    void createRow(DataSource database) throws SQLException {
        this.database = database;
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            prepare(statement);
            statement.execute("CREATE TABLE item (id INT PRIMARY KEY, v INT)");
            statement.execute("INSERT INTO item VALUES (1, 0)");
        }
    }

    /** Anything the database needs before the test, such as a lock timeout long enough to watch. */
    void prepare(Statement statement) throws SQLException {
    }

    private static void update(Connection connection, int v) throws SQLException {
        connection.createStatement().executeUpdate("UPDATE item SET v = " + v + " WHERE id = 1");
    }

    private static Thread updateInBackground(Connection connection) {
        return Thread.ofPlatform().start(() -> {
            try {
                update(connection, 2);
                connection.commit();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static int awaitBlocker(LockWatch watch, int transaction) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int blocker;
        while ((blocker = watch.blocker(transaction)) == LockWatch.NONE && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return blocker;
    }

    @Test
    void namesTheTransactionHoldingTheLock() throws Exception {
        try (LockWatch watch = LockWatch.open(database);
             Connection first = database.getConnection();
             Connection second = database.getConnection()) {
            watch.register(1, first);
            watch.register(2, second);
            first.setAutoCommit(false);
            second.setAutoCommit(false);

            update(first, 1);
            assertThat(watch.blocker(2)).isEqualTo(LockWatch.NONE);
            Thread waiting = updateInBackground(second);

            assertThat(awaitBlocker(watch, 2)).isEqualTo(1);
            assertThat(watch.blocker(1)).isEqualTo(LockWatch.NONE);
            assertThat(watch.sees()).isTrue();

            first.commit();
            waiting.join();
            assertThat(watch.blocker(2)).isEqualTo(LockWatch.NONE);
        }
    }

    @Test
    void saysWhenTheHolderIsOutsideTheRun() throws Exception {
        try (LockWatch watch = LockWatch.open(database);
             Connection outsider = database.getConnection();
             Connection second = database.getConnection()) {
            watch.register(2, second);
            outsider.setAutoCommit(false);
            second.setAutoCommit(false);

            update(outsider, 1);
            Thread waiting = updateInBackground(second);

            assertThat(awaitBlocker(watch, 2)).isEqualTo(LockWatch.OUTSIDE);

            outsider.rollback();
            waiting.join();
        }
    }

    @Test
    void aTransactionItWasNeverToldAboutIsNotWaiting() throws Exception {
        try (LockWatch watch = LockWatch.open(database)) {
            assertThat(watch.blocker(7)).isEqualTo(LockWatch.NONE);
        }
    }
}

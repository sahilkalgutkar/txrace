package com.sahilkalgutkar.txrace.schedule;

import static com.sahilkalgutkar.txrace.schedule.SchedulerTest.deposit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How the scheduler handles steps that wait on locks, on any database the lock watch can ask. */
abstract class LockWaitContract {

    DataSource database;
    Scheduler scheduler;

    @BeforeEach
    void openAccounts(DataSource database) throws SQLException {
        this.database = database;
        this.scheduler = new Scheduler(database);
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            prepare(statement);
            statement.execute("CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL)");
        }
        reset();
    }

    /** Anything the database needs first, such as lock and deadlock timeouts suited to a test. */
    abstract void prepare(Statement statement) throws SQLException;

    void reset() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM account");
            statement.execute("INSERT INTO account VALUES (1, 100), (2, 100)");
        }
    }

    int balance(int account) throws SQLException {
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery(
                     "SELECT balance FROM account WHERE id = " + account)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** Three steps: take from one account, give to the other, commit. */
    static Transaction transfer(int from, int to) {
        return connection -> {
            for (int[] change : new int[][] {{from, -10}, {to, 10}}) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE account SET balance = balance + ? WHERE id = ?")) {
                    update.setInt(1, change[1]);
                    update.setInt(2, change[0]);
                    update.executeUpdate();
                }
            }
            return null;
        };
    }

    @Test
    void aStepWaitingOnALockLetsTheScheduleMoveOn() throws SQLException {
        // Transaction 2's update waits for transaction 1's row lock, and finishes when 1 commits.
        Run run = scheduler.run(Schedule.parse("1 2 1 2 1 2"), deposit(1, 30), deposit(1, 50));

        assertThat(run.results()).containsExactly(new Result.Committed(130), new Result.Committed(150));
        assertThat(run.trace().render()).isEqualTo("""
                  1  c1 t1  SELECT balance FROM account WHERE id = ?  [1]  -> rows
                  2  c2 t1  SELECT balance FROM account WHERE id = ?  [1]  -> rows
                  3  c1 t1  UPDATE account SET balance = ? WHERE id = ?  [130, 1]  -> updated 1
                  4  c2 t1  UPDATE account SET balance = ? WHERE id = ?  [150, 1]  -> updated 1 after waiting for a lock
                  5  c1 t1  COMMIT  -> done
                  6  c2 t1  COMMIT  -> done
                """);
        assertThat(balance(1)).isEqualTo(150);
    }

    @Test
    void waitingDoesNotMakeAScheduleLessRepeatable() throws SQLException {
        List<String> traces = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            reset();
            traces.add(scheduler.run(Schedule.parse("2 1 1 2 1 2"), deposit(1, 30), deposit(1, 50)).trace().render());
        }

        assertThat(traces).containsOnly(traces.getFirst());
        assertThat(traces.getFirst()).contains("after waiting for a lock");
    }

    @Test
    void refusesATurnForATransactionThatIsWaitingOnALock() throws SQLException {
        ScheduleException e = catchThrowableOfType(ScheduleException.class,
                () -> scheduler.run(Schedule.parse("1 2 1 2 2 1"), deposit(1, 30), deposit(1, 50)));

        assertThat(e).hasMessageStartingWith("position 5 of \"1 2 1 2 2 1\" asks for transaction 2, whose step is "
                + "still waiting on a lock held by transaction 1");
        assertThat(balance(1)).isEqualTo(100);
    }

    @Test
    void refusesAScheduleThatRunsOutWhileAStepWaits() {
        ScheduleException e = catchThrowableOfType(ScheduleException.class,
                () -> scheduler.run(Schedule.parse("2 1 2 1"), deposit(1, 30), deposit(1, 50)));

        assertThat(e).hasMessageStartingWith(
                "the schedule ran out while transaction 1 was still waiting on a lock held by transaction 2");
    }

    @Test
    void theDatabaseBreaksADeadlock() throws SQLException {
        // Each transfer locks its first account, then waits for the other's.
        Run run = scheduler.run(Schedule.parse("1 2 1 2 2 1"), transfer(1, 2), transfer(2, 1));

        Result.RolledBack victim = (Result.RolledBack) run.result(deadlockVictim());
        assertThat(((SQLException) victim.cause()).getSQLState()).isEqualTo(deadlockState());
        assertThat(run.result(3 - deadlockVictim())).isEqualTo(new Result.Committed(null));
        assertThat(balance(1) + balance(2)).isEqualTo(200);
    }

    /** Which transfer the database picks to fail. */
    abstract int deadlockVictim();

    abstract String deadlockState();
}

package com.sahilkalgutkar.txrace.schedule;

import static com.sahilkalgutkar.txrace.schedule.SchedulerTest.deposit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sahilkalgutkar.txrace.jdbc.H2Extension;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(H2Extension.class)
class SchedulerFailureTest {

    private DataSource h2;

    @BeforeEach
    void openAccount(DataSource h2) throws SQLException {
        this.h2 = h2;
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL)");
            statement.execute("INSERT INTO account VALUES (1, 100)");
        }
    }

    private int balance() throws SQLException {
        try (Connection connection = h2.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT balance FROM account")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int sessions() throws SQLException {
        try (Connection connection = h2.getConnection();
             ResultSet rows = connection.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS")) {
            rows.next();
            return rows.getInt(1) - 1;
        }
    }

    @Test
    void checksItsArgumentsBeforeOpeningAnything() throws SQLException {
        Scheduler scheduler = new Scheduler(h2);

        assertThatThrownBy(() -> scheduler.run(null, deposit(1, 30))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scheduler.run(Schedule.parse("1"), deposit(1, 30), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Scheduler(h2, Duration.ZERO)).hasMessageContaining("has to be positive");
        assertThat(sessions()).isZero();
    }

    @Test
    void acceptsATimeoutTooLongToCountInNanoseconds() throws SQLException {
        Scheduler scheduler = new Scheduler(h2, ChronoUnit.FOREVER.getDuration());

        Run run = scheduler.run(Schedule.parse("1 1 1"), deposit(1, 30));

        assertThat(run.result(1)).isEqualTo(new Result.Committed(130));
    }

    @Test
    void refusesATransactionThatDoesNotExist() {
        Scheduler scheduler = new Scheduler(h2);

        ScheduleException e = catchThrowableOfType(ScheduleException.class,
                () -> scheduler.run(Schedule.parse("1 3"), deposit(1, 30), deposit(1, 50)));

        assertThat(e).hasMessageStartingWith("position 2 of \"1 3\" names transaction 3, but there are only 2");
        assertThat(e.trace().events()).extracting(event -> event.step().sql()).startsWith(
                "SELECT balance FROM account WHERE id = ?");
    }

    @Test
    void refusesATurnForATransactionThatHasFinished() throws SQLException {
        Scheduler scheduler = new Scheduler(h2);

        assertThatThrownBy(() -> scheduler.run(Schedule.parse("1 1 1 1"), deposit(1, 30)))
                .isInstanceOf(ScheduleException.class)
                .hasMessageStartingWith("position 4 of \"1 1 1 1\" asks for transaction 1, which has already finished: "
                        + "it committed");
        assertThat(balance()).isEqualTo(130);
    }

    @Test
    void saysWhyATransactionFinishedEarly() {
        Transaction refusal = connection -> {
            deposit(1, 0).run(connection);
            throw new IllegalStateException("insufficient funds");
        };
        Scheduler scheduler = new Scheduler(h2);

        ScheduleException e = catchThrowableOfType(ScheduleException.class,
                () -> scheduler.run(Schedule.parse("1 1 1 1"), refusal));

        assertThat(e).hasMessageStartingWith("position 4 of \"1 1 1 1\" asks for transaction 1, which has already "
                + "finished: it rolled back after java.lang.IllegalStateException: insufficient funds");
        assertThat(e.getSuppressed()).singleElement().hasFieldOrPropertyWithValue("message", "insufficient funds");
        assertThat(e.results()).singleElement().isInstanceOf(Result.RolledBack.class);
    }

    @Test
    void refusesAScheduleThatRunsOutEarly() throws SQLException {
        Scheduler scheduler = new Scheduler(h2);

        ScheduleException e = catchThrowableOfType(ScheduleException.class,
                () -> scheduler.run(Schedule.parse("1 1"), deposit(1, 30)));

        assertThat(e).hasMessageStartingWith(
                "the schedule ran out while transaction 1 still had a step to take: COMMIT");
        assertThat(e.trace().render()).endsWith("c1 t1  ROLLBACK  -> done\n");
        assertThat(balance()).isEqualTo(100);
    }

    @Test
    void reportsAStepThatWaitsOnALock() throws SQLException {
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            // Long enough that the scheduler gives up before H2 does.
            statement.execute("SET DEFAULT_LOCK_TIMEOUT 10000");
        }
        Scheduler scheduler = new Scheduler(h2, Duration.ofMillis(300));

        ScheduleException e = catchThrowableOfType(ScheduleException.class,
                () -> scheduler.run(Schedule.parse("1 2 1 2 1 2"), deposit(1, 30), deposit(1, 50)));

        assertThat(e).hasMessageStartingWith("the step transaction 2 took at position 4 of \"1 2 1 2 1 2\" "
                + "did not finish within PT0.3S. It may be waiting on a lock another transaction holds");
        // Abandoning the run lets transaction 1 roll back, which frees the lock, and then
        // transaction 2 finishes its update and rolls back too.
        assertThat(e.trace().render()).contains("c1 t1  ROLLBACK  -> done", "c2 t1  ROLLBACK  -> done");
        assertThat(balance()).isEqualTo(100);
    }

    @Test
    void reportsATransactionThatNeverReachesTheDatabase() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        Transaction stalled = connection -> {
            release.await();
            return deposit(1, 30).run(connection);
        };
        Scheduler scheduler = new Scheduler(h2, Duration.ofMillis(200));

        try {
            assertThatThrownBy(() -> scheduler.run(Schedule.parse("1 1 1"), stalled))
                    .isInstanceOf(ScheduleException.class)
                    .hasMessageContaining("transaction 1 neither took its next step nor finished within PT0.2S")
                    .hasMessageContaining("Still running after the schedule was abandoned: txrace-t1");
        } finally {
            release.countDown();
        }
    }
}

package com.sahilkalgutkar.txrace.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahilkalgutkar.txrace.jdbc.H2Extension;
import com.sahilkalgutkar.txrace.trace.Trace;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(H2Extension.class)
class SchedulerTest {

    private DataSource h2;
    private Scheduler scheduler;

    @BeforeEach
    void openAccounts(DataSource h2) throws SQLException {
        this.h2 = h2;
        this.scheduler = new Scheduler(h2);
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL)");
        }
        reset();
    }

    private void reset() throws SQLException {
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM account");
            statement.execute("INSERT INTO account VALUES (1, 100), (2, 100)");
        }
    }

    private int balance(int account) throws SQLException {
        try (Connection connection = h2.getConnection()) {
            return read(connection, account);
        }
    }

    private static int read(Connection connection, int account) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT balance FROM account WHERE id = ?")) {
            select.setInt(1, account);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** Three steps: read the balance, write the new one, commit. */
    static Transaction deposit(int account, int amount) {
        return connection -> {
            int balance = read(connection, account) + amount;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE account SET balance = ? WHERE id = ?")) {
                update.setInt(1, balance);
                update.setInt(2, account);
                update.executeUpdate();
            }
            return balance;
        };
    }

    /** Two steps: read, commit. */
    static Transaction look(int account) {
        return connection -> read(connection, account);
    }

    private static List<Integer> order(Trace trace) {
        return trace.events().stream().map(e -> e.step().connection()).toList();
    }

    @Test
    void runsOneTransactionStepByStep() throws SQLException {
        Run run = scheduler.run(Schedule.parse("1 1 1"), deposit(1, 30));

        assertThat(run.result(1)).isEqualTo(new Result.Committed(130));
        assertThat(run.trace().render()).isEqualTo("""
                  1  c1 t1  SELECT balance FROM account WHERE id = ?  [1]  -> rows
                  2  c1 t1  UPDATE account SET balance = ? WHERE id = ?  [130, 1]  -> updated 1
                  3  c1 t1  COMMIT  -> done
                """);
        assertThat(balance(1)).isEqualTo(130);
    }

    @Test
    void followsTheScheduleThatLosesAnUpdate() throws SQLException {
        Run run = scheduler.run(Schedule.parse("1 2 1 1 2 2"), deposit(1, 30), deposit(1, 50));

        assertThat(run.results()).containsExactly(new Result.Committed(130), new Result.Committed(150));
        assertThat(order(run.trace())).containsExactly(1, 2, 1, 1, 2, 2);
        assertThat(balance(1)).isEqualTo(150);
    }

    @Test
    void aSerialScheduleKeepsBothDeposits() throws SQLException {
        scheduler.run(Schedule.parse("1 1 1 2 2 2"), deposit(1, 30), deposit(1, 50));

        assertThat(balance(1)).isEqualTo(180);
    }

    @Test
    void theSameScheduleGivesTheSameTraceEveryTime() throws SQLException {
        // Transaction 2 commits before transaction 1 writes, so neither waits on the other's lock.
        Schedule schedule = Schedule.parse("2 1 2 2 1 1");
        List<String> traces = new ArrayList<>();
        List<List<Result>> results = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            reset();
            Run run = scheduler.run(schedule, deposit(1, 30), deposit(1, 50));
            traces.add(run.trace().render());
            results.add(run.results());
        }

        assertThat(traces).containsOnly(traces.getFirst());
        assertThat(results).containsOnly(List.of(new Result.Committed(130), new Result.Committed(150)));
    }

    @Test
    void everyOrderOfIndependentTransactionsRunsAsScheduled() throws SQLException {
        List<Schedule> schedules = Interleavings.of(3, 3, 2);

        for (Schedule schedule : schedules) {
            reset();
            Run run = scheduler.run(schedule, deposit(1, 30), deposit(2, 50), look(2));

            assertThat(order(run.trace())).as(schedule.toString()).isEqualTo(schedule.order());
            assertThat(run.result(1)).isEqualTo(new Result.Committed(130));
            assertThat(run.result(2)).isEqualTo(new Result.Committed(150));
            assertThat(balance(1)).isEqualTo(130);
            assertThat(balance(2)).isEqualTo(150);
        }
        assertThat(schedules).hasSize(560);
    }

    @Test
    void aTransactionThatThrowsIsRolledBackOnItsTurn() throws SQLException {
        Transaction changeOfMind = connection -> {
            deposit(1, 30).run(connection);
            throw new IllegalStateException("changed my mind");
        };

        Run run = scheduler.run(Schedule.parse("1 1 1"), changeOfMind);

        assertThat(run.result(1)).isInstanceOfSatisfying(Result.RolledBack.class,
                rolledBack -> assertThat(rolledBack.cause()).hasMessage("changed my mind"));
        assertThat(run.trace().render()).endsWith("c1 t1  ROLLBACK  -> done\n");
        assertThat(balance(1)).isEqualTo(100);
    }

    @Test
    void aRunCanPickItsStepsAsItGoes() throws SQLException {
        Run picked = scheduler.run((taken, ready) -> Collections.min(ready), List.of(deposit(1, 30), deposit(2, 50)));

        // Always the lowest ready transaction, so 1 runs to the end before 2 starts.
        assertThat(picked.schedule()).isEqualTo(Schedule.parse("1 1 1 2 2 2"));
        reset();
        assertThat(scheduler.run(picked.schedule(), deposit(1, 30), deposit(2, 50)).trace().render())
                .isEqualTo(picked.trace().render());
    }

    @Test
    void theChooserIsOfferedEveryTransactionReadyForItsNextStep() throws SQLException {
        List<Set<Integer>> offered = new ArrayList<>();

        scheduler.run((taken, ready) -> {
            offered.add(ready);
            return Collections.max(ready);
        }, List.of(deposit(1, 30), look(2)));

        // 2 goes first and finishes after its two steps, then 1 takes its three alone.
        assertThat(offered).containsExactly(
                Set.of(1, 2), Set.of(1, 2), Set.of(1), Set.of(1), Set.of(1));
    }

    @Test
    void refusesAChoiceOfATransactionThatIsNotReady() {
        assertThatThrownBy(() -> scheduler.run((taken, ready) -> 7, List.of(deposit(1, 30))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("chose transaction 7, which is not one of [1]");
    }

    @Test
    void aTransactionThatNeverTouchesTheDatabaseNeedsNoTurn() throws SQLException {
        Run run = scheduler.run(Schedule.parse(""), connection -> "nothing to do");

        assertThat(run.result(1)).isEqualTo(new Result.Committed("nothing to do"));
        assertThat(run.trace().events()).isEmpty();
    }
}

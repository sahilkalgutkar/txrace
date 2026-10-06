package com.sahilkalgutkar.txrace.schedule;

import static com.sahilkalgutkar.txrace.schedule.SchedulerTest.deposit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahilkalgutkar.txrace.jdbc.H2Extension;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ExtendWith(H2Extension.class)
class ExplorerTest {

    private DataSource h2;

    @BeforeEach
    void createAccounts(DataSource h2) throws SQLException {
        this.h2 = h2;
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET DEFAULT_LOCK_TIMEOUT 10000");
            statement.execute("CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL)");
        }
    }

    static void openAccounts(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM account");
            statement.execute("INSERT INTO account VALUES (1, 100), (2, 100), (3, 100)");
        }
    }

    static int balance(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT balance FROM account WHERE id = 1")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** {@code reads} reads of one account, then the commit: one step more than it reads. */
    static Transaction reads(int account, int reads) {
        return connection -> {
            for (int i = 0; i < reads; i++) {
                try (PreparedStatement select = connection.prepareStatement("SELECT balance FROM account WHERE id = ?")) {
                    select.setInt(1, account);
                    select.executeQuery().close();
                }
            }
            return null;
        };
    }

    /** How many ways steps of transactions with these step counts can interleave: (a+b+...)! / (a! b! ...). */
    static long interleavings(int... steps) {
        long ways = 1;
        int done = 0;
        for (int count : steps) {
            for (int i = 1; i <= count; i++) {
                ways = ways * (done + i) / i;
            }
            done += count;
        }
        return ways;
    }

    @ParameterizedTest
    @ValueSource(strings = {"1 1", "2 1", "2 2", "1 1 1", "3 1 1", "2 2 1"})
    void runsEveryOrderOfIndependentTransactionsExactlyOnce(String reads) throws SQLException {
        int[] counts = Arrays.stream(reads.split(" ")).mapToInt(Integer::parseInt).toArray();
        List<Transaction> transactions = IntStream.range(0, counts.length)
                .mapToObj(i -> reads(i + 1, counts[i])).toList();

        Exploration exploration = new Explorer(h2, ExplorerTest::openAccounts).explore(transactions);

        long expected = interleavings(Arrays.stream(counts).map(r -> r + 1).toArray());
        List<Schedule> schedules = exploration.runs().stream().map(e -> e.run().schedule()).toList();
        assertThat(exploration.complete()).isTrue();
        assertThat(schedules).hasSize((int) expected).doesNotHaveDuplicates();
        for (Exploration.Explored explored : exploration.runs()) {
            assertThat(explored.run().trace().events()).extracting(e -> e.step().connection())
                    .isEqualTo(explored.run().schedule().order());
        }
    }

    @Test
    void findsEveryWayTwoDepositsCanEnd() throws SQLException {
        Exploration exploration = new Explorer(h2, ExplorerTest::openAccounts)
                .observing(ExplorerTest::balance)
                .explore(deposit(1, 30), deposit(1, 50));

        // Run one after the other they add up. Interleaved, either can overwrite the other.
        assertThat(exploration.runs()).extracting(Exploration.Explored::state)
                .containsOnly(180, 150, 130)
                .contains(180, 150, 130);
        assertThat(exploration.refused()).isEmpty();
    }

    @Test
    void withNoPreemptionsRunsOnlyTheSerialOrders() throws SQLException {
        Exploration exploration = new Explorer(h2, ExplorerTest::openAccounts)
                .withPreemptions(0)
                .explore(reads(1, 1), reads(2, 1), reads(3, 1));

        assertThat(exploration.runs()).extracting(e -> e.run().schedule().toString()).containsExactlyInAnyOrder(
                "1 1 2 2 3 3", "1 1 3 3 2 2", "2 2 1 1 3 3", "2 2 3 3 1 1", "3 3 1 1 2 2", "3 3 2 2 1 1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2 2", "3 1", "1 1", "4 2"})
    void withOnePreemptionTwoTransactionsHaveAsManyOrdersAsSteps(String reads) throws SQLException {
        // Run one to the end, or stop it once for the other to run to the end: a + b orders in all.
        int a = Integer.parseInt(reads.split(" ")[0]) + 1;
        int b = Integer.parseInt(reads.split(" ")[1]) + 1;

        Exploration exploration = new Explorer(h2, ExplorerTest::openAccounts)
                .withPreemptions(1)
                .explore(reads(1, a - 1), reads(2, b - 1));

        assertThat(exploration.runs()).hasSize(a + b);
        for (Exploration.Explored explored : exploration.runs()) {
            List<Integer> order = explored.run().schedule().order();
            assertThat(IntStream.range(1, order.size()).filter(i -> !order.get(i).equals(order.get(i - 1))))
                    .hasSizeLessThanOrEqualTo(2);
        }
    }

    @Test
    void onePreemptionIsEnoughToLoseADeposit() throws SQLException {
        Exploration exploration = new Explorer(h2, ExplorerTest::openAccounts)
                .withPreemptions(1)
                .observing(ExplorerTest::balance)
                .explore(deposit(1, 30), deposit(1, 50));

        assertThat(exploration.runs()).extracting(Exploration.Explored::state).contains(180, 150, 130);
        assertThatThrownBy(() -> new Explorer(h2, ExplorerTest::openAccounts).withPreemptions(-1))
                .hasMessageContaining("can't be negative");
    }

    @Test
    void keepsOrdersTheDatabaseDecidedApartFromTheRest() throws SQLException {
        // H2 has later updates of a row wait on its holder, so one commit can free two of them.
        Exploration exploration = new Explorer(h2, ExplorerTest::openAccounts)
                .observing(ExplorerTest::balance)
                .explore(LockWaitContract.update("UPDATE account SET balance = balance + 1 WHERE id = 1"),
                        LockWaitContract.update("UPDATE account SET balance = balance + 10 WHERE id = 1"),
                        LockWaitContract.update("UPDATE account SET balance = balance + 100 WHERE id = 1"));

        assertThat(exploration.complete()).isTrue();
        assertThat(exploration.refused()).isNotEmpty()
                .allSatisfy(refused -> assertThat(refused.reason()).contains("cannot be replayed exactly"));
        assertThat(exploration.runs()).isNotEmpty().extracting(Exploration.Explored::state).containsOnly(211);
    }

    @Test
    void exploresInTheSameOrderEveryTime() throws SQLException {
        // The order matters once a limit cuts the search short, so it must not change between JVMs.
        Exploration exploration = new Explorer(h2, ExplorerTest::openAccounts)
                .limitedTo(6)
                .explore(reads(1, 1), reads(2, 1), reads(3, 1));

        assertThat(exploration.runs()).extracting(e -> e.run().schedule().toString()).containsExactly(
                "1 1 2 2 3 3", "1 1 2 3 3 2", "1 1 2 3 2 3", "1 1 3 3 2 2", "1 1 3 2 2 3", "1 1 3 2 3 2");
    }

    @Test
    void aDeadlockClosedByAStepThatWasJustFreedEndsBeforeTheNextChoice() throws SQLException {
        // When 1 commits, 3 gets account 1 and then waits for account 2, which 2 holds while it
        // waits for account 3, which 3 holds. That deadlock has to be over before anyone is offered
        // a next step, or what is offered depends on when the database noticed it.
        Scheduler scheduler = new Scheduler(h2);
        List<Integer> prefix = List.of(1, 2, 3, 3, 2, 1);
        Set<List<Integer>> orders = new HashSet<>();
        Set<Set<Integer>> offeredAfterThePrefix = new HashSet<>();

        for (int i = 0; i < 15; i++) {
            openAccounts(h2);
            Run run = scheduler.run((taken, ready) -> {
                if (taken.size() == prefix.size()) {
                    offeredAfterThePrefix.add(ready);
                }
                return taken.size() < prefix.size() ? prefix.get(taken.size()) : Collections.min(ready);
            }, List.of(
                    LockWaitContract.update("UPDATE account SET balance = balance + 1 WHERE id = 1"),
                    twoUpdates("UPDATE account SET balance = balance + 1 WHERE id = 2",
                            "UPDATE account SET balance = balance + 1 WHERE id = 3"),
                    twoUpdates("UPDATE account SET balance = balance + 1 WHERE id = 3",
                            "UPDATE account SET balance = balance + 1 WHERE id IN (1, 2)"),
                    reads(1, 1)));
            orders.add(run.schedule().order());
        }

        assertThat(orders).hasSize(1);
        assertThat(offeredAfterThePrefix).hasSize(1);
    }

    @Test
    void aTransactionThatDoesNotRepeatItselfIsReportedRatherThanEndingTheSearch() throws SQLException {
        AtomicInteger calls = new AtomicInteger();
        Transaction fickle = connection -> reads(1, calls.incrementAndGet() % 2 == 0 ? 1 : 2).run(connection);

        Exploration exploration = new Explorer(h2, ExplorerTest::openAccounts).explore(fickle, reads(2, 1));

        assertThat(exploration.complete()).isTrue();
        assertThat(exploration.refused()).isNotEmpty()
                .allSatisfy(refused -> assertThat(refused.reason()).contains("where the run it branched from was offered"));
    }

    static Transaction twoUpdates(String first, String second) {
        return connection -> {
            connection.createStatement().executeUpdate(first);
            return connection.createStatement().executeUpdate(second);
        };
    }

    @Test
    void saysWhenItStoppedAtItsLimit() throws SQLException {
        Exploration exploration = new Explorer(h2, ExplorerTest::openAccounts)
                .limitedTo(5)
                .explore(reads(1, 2), reads(2, 2));

        assertThat(exploration.complete()).isFalse();
        assertThat(exploration.runs()).hasSize(5);
        assertThatThrownBy(() -> new Explorer(h2, ExplorerTest::openAccounts).limitedTo(0))
                .hasMessageContaining("at least one run");
    }
}

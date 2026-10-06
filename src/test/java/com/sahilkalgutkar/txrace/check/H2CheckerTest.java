package com.sahilkalgutkar.txrace.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.jdbc.H2Extension;
import com.sahilkalgutkar.txrace.schedule.Explorer;
import com.sahilkalgutkar.txrace.schedule.Result;
import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(H2Extension.class)
class H2CheckerTest {

    private Explorer explorer;

    @BeforeEach
    void createAccount(DataSource h2) throws SQLException {
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET DEFAULT_LOCK_TIMEOUT 10000");
            statement.execute("CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL)");
        }
        explorer = new Explorer(h2, Accounts::open).observing(Accounts::balance);
    }

    @Test
    void readingThenWritingTheBalanceFails() throws SQLException {
        Verdict verdict = new Checker(explorer).check(Accounts.deposit(30), Accounts.deposit(50));

        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.counterexample()).hasValueSatisfying(order -> {
            assertThat(order.preemptions()).isEqualTo(1);
            assertThat(order.state()).isIn(130, 150);
        });
        assertThat(verdict.report())
                .contains("orders end in a way no serial order does. The one with the fewest preemptions (1)")
                .contains("transaction 1                                 transaction 2")
                .contains("1 then 2: transaction 1 returned 130, transaction 2 returned 180; observed 180")
                .contains("2 then 1: transaction 1 returned 180, transaction 2 returned 150; observed 180");
    }

    @Test
    void anAtomicIncrementPasses() throws SQLException {
        Verdict verdict = new Checker(explorer).check(Accounts.increment(30), Accounts.increment(50));

        assertThat(verdict.holds()).isTrue();
        assertThat(verdict.violations()).isEmpty();
        assertThat(verdict.report()).endsWith("Every order ended the way some serial order does.\n");
    }

    @Test
    void canIgnoreReturnValuesThatDifferOnEveryRun() throws SQLException {
        Transaction first = withToken(Accounts.increment(30));
        Transaction second = withToken(Accounts.increment(50));

        // A fresh token from each transaction makes every ending unlike every serial one.
        assertThat(new Checker(explorer).check(first, second).holds()).isFalse();
        assertThat(new Checker(explorer).ignoringReturnValues().check(first, second).holds()).isTrue();
    }

    private static Transaction withToken(Transaction transaction) {
        return connection -> {
            transaction.run(connection);
            return UUID.randomUUID().toString();
        };
    }

    @Test
    void canJudgeByAnInvariantInsteadOfTheSerialOrders() throws SQLException {
        // Each deposit returns its amount, so the balance has to be 100 plus what committed.
        Invariant everyDepositCounts = ending -> ending.state().equals(100 + ending.committed().values().stream()
                .mapToInt(amount -> (Integer) amount).sum());
        Checker checker = new Checker(explorer).judgingBy(everyDepositCounts);

        Verdict lost = checker.check(returning(30, Accounts.deposit(30)), returning(50, Accounts.deposit(50)));
        Verdict kept = checker.check(returning(30, Accounts.increment(30)), returning(50, Accounts.increment(50)));

        assertThat(lost.holds()).isFalse();
        assertThat(lost.serial()).isEmpty();
        assertThat(lost.report()).contains("orders end breaking the invariant");
        assertThat(kept.holds()).isTrue();
        assertThat(kept.report()).endsWith("Every order kept the invariant.\n");
    }

    private static Transaction returning(int amount, Transaction transaction) {
        return connection -> {
            transaction.run(connection);
            return amount;
        };
    }

    @Test
    void aTransactionThatAlwaysGivesUpEndsTheSameWayInEveryOrder() throws SQLException {
        Transaction giveUp = connection -> {
            Accounts.increment(1000).run(connection);
            throw new IllegalStateException("over the limit");
        };

        Verdict verdict = new Checker(explorer).check(Accounts.increment(30), giveUp);

        assertThat(verdict.holds()).isTrue();
        assertThat(verdict.serial()).extracting(Verdict.Serial::ending)
                .contains(new Ending(new TreeMap<>(Map.of()), 100));
    }

    @Test
    void givingUpOverWhatItReadIsAFinding() throws SQLException {
        // Run one after another, the reader sees the same balance twice and commits. Interleaved,
        // the increment can land between its reads, and it gives up.
        Transaction reader = connection -> {
            int first = (Integer) Accounts.read(connection);
            if ((Integer) Accounts.read(connection) != first) {
                throw new IllegalStateException("the balance changed under me");
            }
            return first;
        };

        Verdict verdict = new Checker(explorer).check(Accounts.increment(30), reader);

        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.report()).contains("transaction 2 gave up with java.lang.IllegalStateException");
    }

    @Test
    void aTransactionTheDatabaseRefusedIsComparedWithTheOthersAlone() throws SQLException {
        // H2 refuses the second of two REPEATABLE READ deposits to change the row, so nothing is
        // lost, and those orders end like the first deposit run on its own.
        Verdict verdict = new Checker(explorer).check(
                Accounts.at(Connection.TRANSACTION_REPEATABLE_READ, Accounts.deposit(30)),
                Accounts.at(Connection.TRANSACTION_REPEATABLE_READ, Accounts.deposit(50)));

        assertThat(verdict.holds()).isTrue();
        assertThat(verdict.explored().runs()).anySatisfy(order -> assertThat(order.run().results())
                .anySatisfy(result -> assertThat(result).isInstanceOf(Result.RolledBack.class)));
    }
}

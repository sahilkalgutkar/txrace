package com.sahilkalgutkar.txrace.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.jdbc.H2Extension;
import com.sahilkalgutkar.txrace.schedule.Explorer;
import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.TreeMap;
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
    void comparesRunsWhereSomeRolledBackAgainstJustTheOnesThatCommitted() throws SQLException {
        Transaction giveUp = connection -> {
            Accounts.increment(1000).run(connection);
            throw new IllegalStateException("over the limit");
        };

        Verdict verdict = new Checker(explorer).check(Accounts.increment(30), giveUp);

        // The second always rolls back, so every order ends like the first alone.
        assertThat(verdict.holds()).isTrue();
        assertThat(verdict.serial()).extracting(Verdict.Serial::ending)
                .contains(new Ending(new TreeMap<>(Map.of()), 100));
    }
}

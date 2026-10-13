package com.sahilkalgutkar.txrace.zoo;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.check.Checker;
import com.sahilkalgutkar.txrace.check.Verdict;
import com.sahilkalgutkar.txrace.jdbc.PostgresExtension;
import com.sahilkalgutkar.txrace.schedule.Explorer;
import com.sahilkalgutkar.txrace.schedule.Result;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Every specimen at PostgreSQL's default, READ COMMITTED: the checker has to catch the bug, and
 * pass the fix having judged every order. Two preemptions are enough for all of them.
 */
@ExtendWith(PostgresExtension.class)
class PostgresZooTest {

    private static final List<Specimen> ZOO = List.of(new Overdraft(), new DuplicateSignup());

    private DataSource postgres;

    @BeforeEach
    void createTables(DataSource postgres) throws SQLException {
        this.postgres = postgres;
        try (Connection connection = postgres.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DO $$ BEGIN EXECUTE format('ALTER DATABASE %I SET deadlock_timeout = %L', "
                    + "current_database(), '100ms'); END $$");
            for (Specimen specimen : ZOO) {
                specimen.create(statement);
            }
        }
    }

    private Verdict check(Specimen specimen, Specimen.Twin twin) throws SQLException {
        return new Checker(new Explorer(postgres, twin.reset()).observing(specimen::observe).withPreemptions(2))
                .check(twin.transactions());
    }

    @Test
    void bothPaymentsPassTheCheckAndTheWalletGoesNegative() throws SQLException {
        Verdict verdict = check(new Overdraft(), new Overdraft().buggy());

        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.counterexample()).hasValueSatisfying(order -> {
            assertThat(order.state()).isEqualTo(-20);
            assertThat(order.run().results()).containsOnly(new Result.Committed("paid"));
        });
    }

    @Test
    void checkingInTheUpdateDeclinesTheSecondPayment() throws SQLException {
        assertThat(check(new Overdraft(), new Overdraft().fixed()).holds()).isTrue();
    }

    @Test
    void bothSignupsFindTheAddressFreeAndItEndsUpWithTwoAccounts() throws SQLException {
        Verdict verdict = check(new DuplicateSignup(), new DuplicateSignup().buggy());

        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.counterexample()).hasValueSatisfying(order ->
                assertThat(order.state()).isEqualTo(List.of("ada@example.com ada", "ada@example.com eve")));
    }

    @Test
    void lettingTheConstraintDecideTellsTheSecondSignupItIsTaken() throws SQLException {
        assertThat(check(new DuplicateSignup(), new DuplicateSignup().fixed()).holds()).isTrue();
    }
}

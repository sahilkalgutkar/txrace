package com.sahilkalgutkar.txrace.zoo;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.check.Checker;
import com.sahilkalgutkar.txrace.check.Verdict;
import com.sahilkalgutkar.txrace.jdbc.PostgresExtension;
import com.sahilkalgutkar.txrace.schedule.Explorer;
import com.sahilkalgutkar.txrace.schedule.Result;
import com.sahilkalgutkar.txrace.schedule.Transaction;
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

    private static final List<Specimen> ZOO = List.of(new Overdraft(), new DuplicateSignup(), new LastOwner(),
            new RefreshTokenReuse(), new CodeReuse());

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

    private static Specimen.Twin at(int isolation, Specimen.Twin twin) {
        return new Specimen.Twin(twin.reset(), twin.transactions().stream().<Transaction>map(transaction -> connection -> {
            connection.setTransactionIsolation(isolation);
            return transaction.run(connection);
        }).toList());
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

    @Test
    void bothOwnersCountTwoAndTheTeamIsLeftWithNone() throws SQLException {
        Verdict verdict = check(new LastOwner(), new LastOwner().buggy());

        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.counterexample()).hasValueSatisfying(order -> {
            assertThat(order.state()).isEqualTo(List.of());
            assertThat(order.run().results()).containsOnly(new Result.Committed("left"));
        });
    }

    @Test
    void lockingTheTeamFirstMakesTheSecondOwnerCountAgain() throws SQLException {
        assertThat(check(new LastOwner(), new LastOwner().fixed()).holds()).isTrue();
    }

    /**
     * The same fix fails one level up. REPEATABLE READ takes the snapshot when the first
     * statement starts, and the second owner's first statement is the lock it waits on, so its
     * snapshot is from before the first owner left.
     */
    @Test
    void lockingTheTeamIsNotEnoughUnderRepeatableRead() throws SQLException {
        Verdict verdict = check(new LastOwner(), at(Connection.TRANSACTION_REPEATABLE_READ, new LastOwner().fixed()));

        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.counterexample()).hasValueSatisfying(order -> assertThat(order.state()).isEqualTo(List.of()));
    }

    @Test
    void twoRefreshesOfOneTokenLeaveItsFamilyWithTwoLiveTokens() throws SQLException {
        Verdict verdict = check(new RefreshTokenReuse(), new RefreshTokenReuse().buggy());

        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.counterexample()).hasValueSatisfying(order -> {
            assertThat(order.state()).isEqualTo(List.of("presented consumed", "rotated by 1", "rotated by 2"));
            assertThat(order.run().results())
                    .containsExactly(new Result.Committed("rotated by 1"), new Result.Committed("rotated by 2"));
        });
    }

    @Test
    void consumingTheTokenWithAConditionalUpdateTurnsTheSecondRefreshIntoAReplay() throws SQLException {
        assertThat(check(new RefreshTokenReuse(), new RefreshTokenReuse().fixed()).holds()).isTrue();
    }

    /**
     * The race needs READ COMMITTED. Under REPEATABLE READ the second refresh's flush updates a
     * row the first changed after its snapshot, and PostgreSQL refuses it.
     */
    @Test
    void repeatableReadWouldHaveRefusedTheSecondRefresh() throws SQLException {
        assertThat(check(new RefreshTokenReuse(),
                at(Connection.TRANSACTION_REPEATABLE_READ, new RefreshTokenReuse().buggy())).holds()).isTrue();
    }

    @Test
    void twoExchangesOfOneCodeBothGetTokens() throws SQLException {
        Verdict verdict = check(new CodeReuse(), new CodeReuse().buggy());

        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.counterexample()).hasValueSatisfying(order ->
                assertThat(order.state()).isEqualTo(List.of("code consumed", "issued to 1", "issued to 2")));
    }

    @Test
    void consumingTheCodeWithAConditionalUpdateTurnsTheSecondExchangeIntoAReplay() throws SQLException {
        assertThat(check(new CodeReuse(), new CodeReuse().fixed()).holds()).isTrue();
    }
}

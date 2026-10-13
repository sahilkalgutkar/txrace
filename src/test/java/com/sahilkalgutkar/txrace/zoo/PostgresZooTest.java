package com.sahilkalgutkar.txrace.zoo;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.check.Checker;
import com.sahilkalgutkar.txrace.check.Verdict;
import com.sahilkalgutkar.txrace.jdbc.PostgresExtension;
import com.sahilkalgutkar.txrace.schedule.Explorer;
import com.sahilkalgutkar.txrace.schedule.Result;
import com.sahilkalgutkar.txrace.schedule.Run;
import com.sahilkalgutkar.txrace.schedule.Schedule;
import com.sahilkalgutkar.txrace.schedule.Scheduler;
import com.sahilkalgutkar.txrace.schedule.Transaction;
import com.sahilkalgutkar.txrace.trace.Trace;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
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

    private static boolean refusedByTheDatabase(Result result) {
        return result instanceof Result.RolledBack rolledBack && rolledBack.cause() instanceof SQLException;
    }

    /**
     * A fix passes only if its transactions did their work. The checker counts a transaction that
     * failed with an SQLException as refused by the database and leaves it out, so a fix whose
     * statements always failed would end like a serial run of nothing at all, and hold.
     */
    private static void assertHoldsWithNothingRefused(Verdict verdict) {
        assertThat(verdict.holds()).isTrue();
        assertThat(verdict.explored().runs()).allSatisfy(order -> {
            assertThat(order.run().results()).noneMatch(PostgresZooTest::refusedByTheDatabase);
            assertThat(order.run().results()).hasAtLeastOneElementOfType(Result.Committed.class);
        });
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
        assertHoldsWithNothingRefused(check(new Overdraft(), new Overdraft().fixed()));
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
        assertHoldsWithNothingRefused(check(new DuplicateSignup(), new DuplicateSignup().fixed()));
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
        assertHoldsWithNothingRefused(check(new LastOwner(), new LastOwner().fixed()));
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
        assertHoldsWithNothingRefused(check(new RefreshTokenReuse(), new RefreshTokenReuse().fixed()));
    }

    /**
     * The race needs READ COMMITTED. Under REPEATABLE READ the second refresh's flush updates a
     * row the first changed after its snapshot, and PostgreSQL refuses it.
     */
    @Test
    void repeatableReadWouldHaveRefusedTheSecondRefresh() throws SQLException {
        Verdict verdict = check(new RefreshTokenReuse(),
                at(Connection.TRANSACTION_REPEATABLE_READ, new RefreshTokenReuse().buggy()));

        assertThat(verdict.holds()).isTrue();
        List<Result> results = verdict.explored().runs().stream().flatMap(order -> order.run().results().stream()).toList();
        assertThat(results).filteredOn(PostgresZooTest::refusedByTheDatabase).isNotEmpty().allSatisfy(result ->
                assertThat(((SQLException) ((Result.RolledBack) result).cause()).getSQLState()).isEqualTo("40001"));
        assertThat(verdict.explored().runs()).allSatisfy(order ->
                assertThat(order.run().results()).hasAtLeastOneElementOfType(Result.Committed.class));
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
        assertHoldsWithNothingRefused(check(new CodeReuse(), new CodeReuse().fixed()));
    }

    /**
     * Why trust-platform revokes the family only once the losing grant has rolled back. The
     * loser's conditional update waits for the winner and then changes nothing, but PostgreSQL
     * keeps the row locked until the loser ends, so revoking the token before then has to wait.
     * Started from inside the loser's own request, that wait would never end.
     */
    @Test
    void theRefreshThatLosesStillHoldsTheTokensRow() throws SQLException {
        RefreshTokenReuse.issued(postgres);
        Transaction revoke = connection -> {
            Map<String, Object> token = TrustPlatform.findTokenByHash(connection, RefreshTokenReuse.HASH);
            token.put("revoked_at", Timestamp.from(TrustPlatform.NOW));
            TrustPlatform.updateToken(connection, token);
            return "revoked";
        };

        Run run = new Scheduler(postgres).run(Schedule.parse("1 2 1 2 1 1 1 3 3 2 3"),
                RefreshTokenReuse.consumeIfUnused(1), RefreshTokenReuse.consumeIfUnused(2), revoke);

        assertThat(run.result(1)).isEqualTo(new Result.Committed("rotated by 1"));
        assertThat(run.result(2)).isInstanceOfSatisfying(Result.RolledBack.class,
                rolledBack -> assertThat(rolledBack.cause()).isInstanceOf(TrustPlatform.Replay.class));
        assertThat(run.result(3)).isEqualTo(new Result.Committed("revoked"));
        Trace.Event revocation = run.trace().of(3).stream()
                .filter(event -> event.step().sql() != null && event.step().sql().startsWith("update"))
                .findFirst().orElseThrow();
        assertThat(revocation.waited()).as(run.trace().render()).isTrue();
    }
}

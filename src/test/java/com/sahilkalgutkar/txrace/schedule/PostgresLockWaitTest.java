package com.sahilkalgutkar.txrace.schedule;

import static com.sahilkalgutkar.txrace.schedule.SchedulerTest.deposit;
import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.jdbc.PostgresExtension;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(PostgresExtension.class)
class PostgresLockWaitTest extends LockWaitContract {

    @Override
    void prepare(Statement statement) throws SQLException {
        // Sessions opened from here on look for deadlocks after 100ms instead of a second.
        statement.execute("DO $$ BEGIN EXECUTE format('ALTER DATABASE %I SET deadlock_timeout = %L', "
                + "current_database(), '100ms'); END $$");
    }

    /**
     * PostgreSQL looks for a cycle only once a step has waited for deadlock_timeout. Transaction
     * 1 started waiting first, so its check runs first, finds the cycle, and fails itself.
     */
    @Override
    int deadlockVictim() {
        return 1;
    }

    @Override
    String deadlockState() {
        return "40P01";
    }

    @Test
    void waitersForOneRowGoInTheOrderTheyQueued() throws SQLException {
        // The third update queues behind the second, not behind the holder, so when the holder
        // commits only the second goes.
        Run run = scheduler.run(Schedule.parse("1 2 3 1 2 3"),
                update("UPDATE account SET balance = balance + 1 WHERE id = 1"),
                update("UPDATE account SET balance = balance + 10 WHERE id = 1"),
                update("UPDATE account SET balance = balance + 100 WHERE id = 1"));

        assertThat(run.results()).containsOnly(new Result.Committed(1));
        assertThat(balance(1)).isEqualTo(211);
    }

    @Test
    void repeatableReadRefusesTheUpdateInsteadOfLosingIt() throws SQLException {
        Transaction first = repeatableRead(deposit(1, 30));
        Transaction second = repeatableRead(deposit(1, 50));

        Run run = scheduler.run(Schedule.parse("1 2 1 2 1 2"), first, second);

        // Once transaction 1 commits, 2's waiting update finds the row changed since its snapshot.
        assertThat(run.result(1)).isEqualTo(new Result.Committed(130));
        assertThat(run.result(2)).isInstanceOfSatisfying(Result.RolledBack.class,
                rolledBack -> assertThat(((SQLException) rolledBack.cause()).getSQLState()).isEqualTo("40001"));
        assertThat(run.trace().render()).contains("-> failed 40001 after waiting for a lock");
        assertThat(balance(1)).isEqualTo(130);
    }

    private static Transaction repeatableRead(Transaction transaction) {
        return connection -> {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return transaction.run(connection);
        };
    }
}

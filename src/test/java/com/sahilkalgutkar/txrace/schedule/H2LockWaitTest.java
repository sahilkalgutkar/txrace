package com.sahilkalgutkar.txrace.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sahilkalgutkar.txrace.jdbc.H2Extension;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(H2Extension.class)
class H2LockWaitTest extends LockWaitContract {

    @Override
    void prepare(Statement statement) throws SQLException {
        statement.execute("SET DEFAULT_LOCK_TIMEOUT 10000");
    }

    /** H2 looks for a cycle as soon as a step starts waiting, and fails its youngest member. */
    @Override
    int victimWhenTheOldestClosesTheCycle() {
        return 2;
    }

    @Override
    String deadlockState() {
        return "40001";
    }

    @Test
    void waitersForOneRowRaceWhenItIsFreed() {
        // H2 has both later updates wait on the holder itself, so its commit frees both at once.
        ScheduleException e = catchThrowableOfType(ScheduleException.class,
                () -> scheduler.run(Schedule.parse("1 2 3 1 2 3"),
                        update("UPDATE account SET balance = balance + 1 WHERE id = 1"),
                        update("UPDATE account SET balance = balance + 10 WHERE id = 1"),
                        update("UPDATE account SET balance = balance + 100 WHERE id = 1")));

        assertThat(e).hasMessageContaining("stop waiting at once");
    }

    @Test
    void aDeadlockVictimStopsCountingAsAHolderAtOnce() throws SQLException {
        // H2 rolls the victim back before the survivor's thread wakes, and for a moment still names
        // the victim as what the survivor waits behind. The victim must not count as a holder then.
        Set<List<Result.Committed>> outcomes = new HashSet<>();
        for (int i = 0; i < 150; i++) {
            reset();
            Run run = scheduler.run(Schedule.parse("1 2 1 2 1 2"), transfer(1, 2), transfer(2, 1));
            assertThat(run.result(2)).isInstanceOf(Result.RolledBack.class);
            outcomes.add(List.of((Result.Committed) run.result(1)));
        }

        assertThat(outcomes).containsExactly(List.of(new Result.Committed(null)));
    }
}

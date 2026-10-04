package com.sahilkalgutkar.txrace.schedule;

import static org.assertj.core.api.Assertions.assertThat;

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

    /** H2 looks for a cycle as soon as a step starts waiting, so the step that closes it fails. */
    @Override
    int deadlockVictim() {
        return 2;
    }

    @Override
    String deadlockState() {
        return "40001";
    }

    @Test
    void aDeadlockVictimStopsCountingAsAHolderAtOnce() throws SQLException {
        // H2 rolls the victim back before the survivor's thread wakes, and for a moment still names
        // the victim as what the survivor waits behind. The victim must not count as a holder then.
        Set<List<Result.Committed>> outcomes = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            reset();
            Run run = scheduler.run(Schedule.parse("1 2 1 2 1 2"), transfer(1, 2), transfer(2, 1));
            assertThat(run.result(2)).isInstanceOf(Result.RolledBack.class);
            outcomes.add(List.of((Result.Committed) run.result(1)));
        }

        assertThat(outcomes).containsExactly(List.of(new Result.Committed(null)));
    }
}

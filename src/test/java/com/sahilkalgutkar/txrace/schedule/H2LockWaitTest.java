package com.sahilkalgutkar.txrace.schedule;

import com.sahilkalgutkar.txrace.jdbc.H2Extension;
import java.sql.SQLException;
import java.sql.Statement;
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
}

package com.sahilkalgutkar.txrace.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.jdbc.H2Extension;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(H2Extension.class)
class H2LockWatchTest extends LockWatchContract {

    @Override
    void prepare(Statement statement) throws SQLException {
        // H2 gives up on a lock after a second by default, which is not long enough to watch.
        statement.execute("SET DEFAULT_LOCK_TIMEOUT 10000");
    }

    @Test
    void aDatabaseItCannotAskIsNeverWaiting(DataSource h2) throws Exception {
        try (LockWatch watch = LockWatch.open(OtherDatabase.over(h2)); Connection connection = h2.getConnection()) {
            watch.register(1, connection);

            assertThat(watch.sees()).isFalse();
            assertThat(watch.blocker(1)).isEqualTo(LockWatch.NONE);
        }
    }
}

package com.sahilkalgutkar.txrace.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.jdbc.H2Extension;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
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
    void saysItCannotSeeWhenTheUserIsNotAnAdmin(DataSource h2) throws Exception {
        try (Connection connection = h2.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE USER app PASSWORD 'app'");
            statement.execute("GRANT SELECT, UPDATE ON item TO app");
        }
        JdbcDataSource app = new JdbcDataSource();
        // Without the URL's settings: only an admin may set them, and the database is already open.
        String url = ((JdbcDataSource) h2).getURL();
        app.setURL(url.substring(0, url.indexOf(';')));
        app.setUser("app");
        app.setPassword("app");

        try (LockWatch watch = LockWatch.open(app); Connection connection = app.getConnection()) {
            assertThat(watch.sees()).isTrue();
            watch.register(1, connection);

            assertThat(watch.sees()).isFalse();
        }
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

package com.sahilkalgutkar.txrace.schedule;

import static com.sahilkalgutkar.txrace.schedule.SchedulerTest.deposit;
import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.jdbc.PostgresExtension;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(PostgresExtension.class)
class PostgresSchedulerTest {

    private DataSource postgres;
    private Scheduler scheduler;

    @BeforeEach
    void openAccount(DataSource postgres) throws SQLException {
        this.postgres = postgres;
        this.scheduler = new Scheduler(postgres);
        try (Connection connection = postgres.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL)");
            statement.execute("INSERT INTO account VALUES (1, 100)");
        }
    }

    private int balance() throws SQLException {
        try (Connection connection = postgres.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT balance FROM account WHERE id = 1")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @Test
    void losesTheUpdateUnderReadCommitted() throws SQLException {
        Run run = scheduler.run(Schedule.parse("1 2 1 1 2 2"), deposit(1, 30), deposit(1, 50));

        assertThat(run.results()).containsExactly(new Result.Committed(130), new Result.Committed(150));
        assertThat(balance()).isEqualTo(150);
    }

    @Test
    void doesNotStartATransactionEarlyWhenThePoolHandsOutConnectionsWithAutocommitOff() throws SQLException {
        try (Connection connection = postgres.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DO $$ BEGIN EXECUTE format('ALTER DATABASE %I SET default_transaction_isolation = %L', "
                    + "current_database(), 'repeatable read'); END $$");
        }
        Scheduler fromAPool = new Scheduler(autocommitOff(postgres));

        Run run = fromAPool.run(Schedule.parse("1 1 1 2 2 2"), deposit(1, 30), deposit(1, 50));

        // Run one after the other, each sees the other's work, unless its snapshot was taken early.
        assertThat(run.results()).containsExactly(new Result.Committed(130), new Result.Committed(180));
    }

    /** Connections that arrive with autocommit off, the way a pool configured for it hands them out. */
    private static DataSource autocommitOff(DataSource real) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Connection connection = real.getConnection();
                    connection.setAutoCommit(false);
                    return connection;
                });
    }

    @Test
    void keepsBothDepositsWhenRunOneAfterTheOther() throws SQLException {
        scheduler.run(Schedule.parse("1 1 1 2 2 2"), deposit(1, 30), deposit(1, 50));

        assertThat(balance()).isEqualTo(180);
    }
}

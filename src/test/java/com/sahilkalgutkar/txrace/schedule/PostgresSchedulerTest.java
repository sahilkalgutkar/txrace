package com.sahilkalgutkar.txrace.schedule;

import static com.sahilkalgutkar.txrace.schedule.SchedulerTest.deposit;
import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.jdbc.PostgresExtension;
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
    void keepsBothDepositsWhenRunOneAfterTheOther() throws SQLException {
        scheduler.run(Schedule.parse("1 1 1 2 2 2"), deposit(1, 30), deposit(1, 50));

        assertThat(balance()).isEqualTo(180);
    }
}

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
class PostgresExplorerTest {

    private Explorer explorer;

    @BeforeEach
    void createAccounts(DataSource postgres) throws SQLException {
        try (Connection connection = postgres.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DO $$ BEGIN EXECUTE format('ALTER DATABASE %I SET deadlock_timeout = %L', "
                    + "current_database(), '100ms'); END $$");
            statement.execute("CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL)");
        }
        explorer = new Explorer(postgres, ExplorerTest::openAccounts);
    }

    private static int total(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT SUM(balance) FROM account")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @Test
    void findsEveryWayTwoDepositsCanEnd() throws SQLException {
        Exploration exploration = explorer.observing(ExplorerTest::balance).explore(deposit(1, 30), deposit(1, 50));

        assertThat(exploration.runs()).extracting(Exploration.Explored::state).containsOnly(180, 150, 130)
                .contains(180, 150, 130);
    }

    @Test
    void updatesOfOneRowAllEndAtTheSameBalance() throws SQLException {
        Exploration exploration = explorer.observing(ExplorerTest::balance)
                .explore(LockWaitContract.update("UPDATE account SET balance = balance + 1 WHERE id = 1"),
                        LockWaitContract.update("UPDATE account SET balance = balance + 10 WHERE id = 1"),
                        LockWaitContract.update("UPDATE account SET balance = balance + 100 WHERE id = 1"));

        // Usually nothing is refused, but now and then an update overtakes the one ahead of it.
        assertThat(exploration.refused())
                .allSatisfy(refused -> assertThat(refused.reason()).contains("cannot be replayed exactly"));
        assertThat(exploration.runs()).hasSizeGreaterThan(20).extracting(Exploration.Explored::state)
                .containsOnly(211);
    }

    @Test
    void runsThroughDeadlocksAndKeepsTheMoney() throws SQLException {
        Exploration exploration = explorer.observing(PostgresExplorerTest::total)
                .explore(LockWaitContract.transfer(1, 2), LockWaitContract.transfer(2, 1));

        assertThat(exploration.complete()).isTrue();
        assertThat(exploration.runs()).extracting(Exploration.Explored::state).containsOnly(300);
        assertThat(exploration.runs()).anySatisfy(explored -> assertThat(explored.run().results())
                .anySatisfy(result -> assertThat(result).isInstanceOf(Result.RolledBack.class)));
    }
}

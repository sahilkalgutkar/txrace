package com.sahilkalgutkar.txrace.check;

import static com.sahilkalgutkar.txrace.check.Accounts.at;
import static com.sahilkalgutkar.txrace.check.Accounts.deposit;
import static java.sql.Connection.TRANSACTION_REPEATABLE_READ;
import static java.sql.Connection.TRANSACTION_SERIALIZABLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.jdbc.PostgresExtension;
import com.sahilkalgutkar.txrace.schedule.Explorer;
import com.sahilkalgutkar.txrace.schedule.Result;
import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(PostgresExtension.class)
class PostgresCheckerTest {

    private DataSource postgres;

    @BeforeEach
    void createTables(DataSource postgres) throws SQLException {
        this.postgres = postgres;
        try (Connection connection = postgres.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DO $$ BEGIN EXECUTE format('ALTER DATABASE %I SET deadlock_timeout = %L', "
                    + "current_database(), '100ms'); END $$");
            statement.execute("CREATE TABLE account (id INT PRIMARY KEY, balance INT NOT NULL)");
            statement.execute("CREATE TABLE doctor (name TEXT PRIMARY KEY, on_call BOOLEAN NOT NULL)");
        }
    }

    private Checker accounts() {
        return new Checker(new Explorer(postgres, Accounts::open).observing(Accounts::balance));
    }

    private Checker doctors() {
        return new Checker(new Explorer(postgres, PostgresCheckerTest::bothOnCall)
                .observing(PostgresCheckerTest::onCall));
    }

    static void bothOnCall(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM doctor");
            statement.execute("INSERT INTO doctor VALUES ('alice', true), ('bob', true)");
        }
    }

    static Object onCall(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection();
             ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM doctor WHERE on_call")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /** Goes off call, but only if someone else would still be on call. */
    static Transaction goOffCall(String name) {
        return connection -> {
            int onCall;
            try (ResultSet rows = connection.createStatement()
                    .executeQuery("SELECT COUNT(*) FROM doctor WHERE on_call")) {
                rows.next();
                onCall = rows.getInt(1);
            }
            if (onCall >= 2) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE doctor SET on_call = false WHERE name = ?")) {
                    update.setString(1, name);
                    update.executeUpdate();
                }
            }
            return null;
        };
    }

    @Test
    void readCommittedLosesADeposit() throws SQLException {
        Verdict verdict = accounts().check(deposit(30), deposit(50));

        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.counterexample()).hasValueSatisfying(order -> assertThat(order.state()).isIn(130, 150));
    }

    @Test
    void repeatableReadRefusesTheSecondWriterInstead() throws SQLException {
        Verdict verdict = accounts().check(at(TRANSACTION_REPEATABLE_READ, deposit(30)),
                at(TRANSACTION_REPEATABLE_READ, deposit(50)));

        // The orders that would lose a deposit end with one transaction refused, which is like
        // the other one running alone.
        assertThat(verdict.holds()).isTrue();
        assertThat(verdict.explored().runs()).anySatisfy(order -> assertThat(order.run().results())
                .anySatisfy(result -> assertThat(result).isInstanceOf(Result.RolledBack.class)));
    }

    @Test
    void writeSkewGetsPastRepeatableRead() throws SQLException {
        Verdict verdict = doctors().check(at(TRANSACTION_REPEATABLE_READ, goOffCall("alice")),
                at(TRANSACTION_REPEATABLE_READ, goOffCall("bob")));

        // Each saw two on call in its own snapshot, so both went off, and nobody is left.
        assertThat(verdict.holds()).isFalse();
        assertThat(verdict.counterexample()).hasValueSatisfying(order -> assertThat(order.state()).isEqualTo(0));
    }

    @Test
    void serializableStopsWriteSkew() throws SQLException {
        Verdict verdict = doctors().check(at(TRANSACTION_SERIALIZABLE, goOffCall("alice")),
                at(TRANSACTION_SERIALIZABLE, goOffCall("bob")));

        assertThat(verdict.holds()).isTrue();
    }
}

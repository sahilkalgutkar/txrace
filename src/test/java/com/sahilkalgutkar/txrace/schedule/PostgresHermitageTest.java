package com.sahilkalgutkar.txrace.schedule;

import com.sahilkalgutkar.txrace.jdbc.PostgresExtension;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtendWith;

/** PostgreSQL's row of the table in Hermitage's README, which txrace has to reproduce. */
@ExtendWith(PostgresExtension.class)
class PostgresHermitageTest extends HermitageContract {

    @Override
    void prepare(Statement statement) throws SQLException {
        statement.execute("DO $$ BEGIN EXECUTE format('ALTER DATABASE %I SET deadlock_timeout = %L', "
                + "current_database(), '100ms'); END $$");
    }

    @Override
    List<Level> levels() {
        return List.of(new Level("read committed", Connection.TRANSACTION_READ_COMMITTED),
                new Level("repeatable read", Connection.TRANSACTION_REPEATABLE_READ),
                new Level("serializable", Connection.TRANSACTION_SERIALIZABLE));
    }

    @Override
    Map<String, List<String>> prevented() {
        Map<String, List<String>> prevented = new LinkedHashMap<>();
        prevented.put("read committed", List.of("G0", "G1a", "G1b", "G1c", "OTV"));
        prevented.put("repeatable read", List.of("G0", "G1a", "G1b", "G1c", "OTV", "PMP", "P4", "G-single"));
        prevented.put("serializable",
                List.of("G0", "G1a", "G1b", "G1c", "OTV", "PMP", "P4", "G-single", "G2-item", "G2"));
        return prevented;
    }
}

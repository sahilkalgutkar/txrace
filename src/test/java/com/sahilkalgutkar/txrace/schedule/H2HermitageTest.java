package com.sahilkalgutkar.txrace.schedule;

import com.sahilkalgutkar.txrace.jdbc.H2Extension;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * H2 has no row in Hermitage's table, so this one is what txrace measured, checked against H2's
 * own documentation. It agrees: dirty reads only under READ UNCOMMITTED, and SERIALIZABLE, which
 * the documentation calls partial because it "doesn't ensure equivalence of concurrent and
 * serializable execution of transactions that perform write operations", lets write skew through
 * like snapshot isolation does.
 */
@ExtendWith(H2Extension.class)
class H2HermitageTest extends HermitageContract {

    @Override
    void prepare(Statement statement) throws SQLException {
        statement.execute("SET DEFAULT_LOCK_TIMEOUT 10000");
    }

    @Override
    List<Level> levels() {
        return List.of(new Level("read uncommitted", Connection.TRANSACTION_READ_UNCOMMITTED),
                new Level("read committed", Connection.TRANSACTION_READ_COMMITTED),
                new Level("repeatable read", Connection.TRANSACTION_REPEATABLE_READ),
                new Level("serializable", Connection.TRANSACTION_SERIALIZABLE));
    }

    @Override
    Map<String, List<String>> prevented() {
        Map<String, List<String>> prevented = new LinkedHashMap<>();
        prevented.put("read uncommitted", List.of("G0"));
        prevented.put("read committed", List.of("G0", "G1a", "G1b", "G1c", "OTV"));
        prevented.put("repeatable read", List.of("G0", "G1a", "G1b", "G1c", "OTV", "PMP", "P4", "G-single"));
        prevented.put("serializable", List.of("G0", "G1a", "G1b", "G1c", "OTV", "PMP", "P4", "G-single"));
        return prevented;
    }
}

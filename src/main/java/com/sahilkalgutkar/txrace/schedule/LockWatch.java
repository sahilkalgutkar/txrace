package com.sahilkalgutkar.txrace.schedule;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Asks the database, over a connection of its own, whether a transaction is waiting on a lock and
 * which transaction holds it. Without this, a step stuck on a lock looks the same as a slow one.
 */
abstract class LockWatch implements AutoCloseable {

    /** Not waiting on any lock. */
    static final int NONE = -1;
    /** Waiting on a lock held by a session outside the run. */
    static final int OUTSIDE = 0;

    private final Map<Integer, Long> sessions = new HashMap<>();

    /** A watch for the database behind {@code dataSource}, or one that never sees a wait if it is not supported. */
    static LockWatch open(DataSource dataSource) throws SQLException {
        Connection side = dataSource.getConnection();
        try {
            side.setAutoCommit(true);
            return switch (side.getMetaData().getDatabaseProductName()) {
                case "PostgreSQL" -> new Postgres(side);
                case "H2" -> new H2(side);
                default -> {
                    side.close();
                    yield new Blind();
                }
            };
        } catch (SQLException | RuntimeException e) {
            side.close();
            throw e;
        }
    }

    /**
     * Remembers which session runs transaction n. {@code connection} is the driver's own, so the
     * query that asks never becomes a step, and it must still be in autocommit mode: on
     * PostgreSQL, a query with autocommit off would start the transaction and take its snapshot
     * before the schedule says so.
     */
    void register(int transaction, Connection connection) throws SQLException {
        sessions.put(transaction, session(connection));
    }

    /**
     * The transactions transaction n is waiting behind, with {@link #OUTSIDE} for a session outside
     * the run. Empty if it is not waiting.
     */
    List<Integer> blockers(int transaction) throws SQLException {
        Long session = sessions.get(transaction);
        if (session == null) {
            return List.of();
        }
        return Arrays.stream(blockersOf(session)).mapToObj(holder -> sessions.entrySet().stream()
                .filter(entry -> entry.getValue() == holder)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(OUTSIDE)).toList();
    }

    /** The first of {@link #blockers}, or {@link #NONE}. */
    int blocker(int transaction) throws SQLException {
        List<Integer> blockers = blockers(transaction);
        return blockers.isEmpty() ? NONE : blockers.getFirst();
    }

    boolean sees() {
        return true;
    }

    @Override
    public abstract void close() throws SQLException;

    abstract long session(Connection connection) throws SQLException;

    /** The sessions {@code session} is waiting behind, none if it is not waiting. */
    abstract long[] blockersOf(long session) throws SQLException;

    private static long single(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static final class Postgres extends LockWatch {
        private final Connection side;
        private final PreparedStatement blockers;

        Postgres(Connection side) throws SQLException {
            this.side = side;
            // pg_blocking_pids reads the lock table, which changes as soon as a holder lets go,
            // so it never reports a waiter that has already been granted its lock.
            this.blockers = side.prepareStatement("SELECT pg_blocking_pids(?)");
        }

        @Override
        long session(Connection connection) throws SQLException {
            return single(connection, "SELECT pg_backend_pid()");
        }

        // Can name more than one: the holder, and anyone queued ahead for the same lock.
        @Override
        long[] blockersOf(long session) throws SQLException {
            blockers.setInt(1, (int) session);
            try (ResultSet rows = blockers.executeQuery()) {
                rows.next();
                Array pids = rows.getArray(1);
                return Arrays.stream((Integer[]) pids.getArray()).mapToLong(Integer::longValue).toArray();
            }
        }

        @Override
        public void close() throws SQLException {
            side.close();
        }
    }

    private static final class H2 extends LockWatch {
        private final Connection side;
        private final PreparedStatement blockers;

        H2(Connection side) throws SQLException {
            this.side = side;
            this.blockers = side.prepareStatement(
                    "SELECT BLOCKER_ID FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID = ?");
        }

        @Override
        long session(Connection connection) throws SQLException {
            return single(connection, "SELECT SESSION_ID()");
        }

        @Override
        long[] blockersOf(long session) throws SQLException {
            blockers.setLong(1, session);
            try (ResultSet rows = blockers.executeQuery()) {
                if (!rows.next()) {
                    return new long[0];
                }
                long holder = rows.getLong(1);
                return rows.wasNull() ? new long[0] : new long[] {holder};
            }
        }

        @Override
        public void close() throws SQLException {
            side.close();
        }
    }

    /** For databases it does not know how to ask. Every slow step then runs into the timeout. */
    private static final class Blind extends LockWatch {
        @Override
        void register(int transaction, Connection connection) {
        }

        @Override
        boolean sees() {
            return false;
        }

        @Override
        long session(Connection connection) {
            return NONE;
        }

        @Override
        long[] blockersOf(long session) {
            return new long[0];
        }

        @Override
        public void close() {
        }
    }
}

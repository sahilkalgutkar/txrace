package com.sahilkalgutkar.txrace.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One round trip a transaction makes to the database.
 *
 * <p>Connections are numbered from 1 in the order they were opened. The transaction number starts
 * at 1 on each connection and goes up every time a transaction on it ends. For a batch,
 * {@code parameters} holds one list per row rather than one value per placeholder.
 * {@code autoCommit} is set on a statement that ran with autocommit on, which makes it a
 * transaction by itself.
 */
public record Step(int connection, int transaction, Kind kind, String sql, List<Object> parameters,
        boolean autoCommit) {

    public enum Kind { STATEMENT, BATCH, COMMIT, ROLLBACK }

    public Step {
        if (connection < 1 || transaction < 1) {
            throw new IllegalArgumentException("connection and transaction numbers start at 1");
        }
        Objects.requireNonNull(kind, "kind");
        boolean ends = kind == Kind.COMMIT || kind == Kind.ROLLBACK;
        if (ends != (sql == null)) {
            throw new IllegalArgumentException(kind + (ends ? " carries no SQL" : " needs SQL"));
        }
        // List.copyOf would reject nulls, and a NULL parameter is ordinary.
        parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    public Step(int connection, int transaction, Kind kind, String sql, List<Object> parameters) {
        this(connection, transaction, kind, sql, parameters, false);
    }

    public static Step statement(int connection, int transaction, String sql, List<Object> parameters) {
        return new Step(connection, transaction, Kind.STATEMENT, sql, parameters);
    }

    public static Step commit(int connection, int transaction) {
        return new Step(connection, transaction, Kind.COMMIT, null, List.of());
    }

    public static Step rollback(int connection, int transaction) {
        return new Step(connection, transaction, Kind.ROLLBACK, null, List.of());
    }

    /** True when this step finishes the transaction it belongs to, whether or not it succeeds. */
    public boolean endsTransaction() {
        return kind == Kind.COMMIT || kind == Kind.ROLLBACK || autoCommit;
    }
}

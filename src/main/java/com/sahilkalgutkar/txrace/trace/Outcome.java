package com.sahilkalgutkar.txrace.trace;

import java.sql.SQLException;
import java.util.List;

/** What the database answered to a {@link Step}. */
public sealed interface Outcome {

    /** The statement returned a result set. */
    record Rows() implements Outcome {}

    record Updated(long count) implements Outcome {}

    record Batch(List<Long> counts) implements Outcome {
        public Batch {
            counts = List.copyOf(counts);
        }
    }

    /** A commit, a rollback, or a statement that returned neither rows nor a count. */
    record Done() implements Outcome {}

    /** The driver threw. {@code sqlState} is null when it was not an {@link SQLException}. */
    record Failed(String sqlState, int errorCode, String message) implements Outcome {}

    static Outcome failed(Throwable thrown) {
        if (thrown instanceof SQLException e) {
            return new Failed(e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
        return new Failed(null, 0, thrown.toString());
    }
}

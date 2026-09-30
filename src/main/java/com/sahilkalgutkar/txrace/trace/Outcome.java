package com.sahilkalgutkar.txrace.trace;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.Arrays;
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

    /**
     * The driver threw. {@code sqlState} is null when it was not an {@link SQLException}. For a
     * batch, {@code counts} holds what the driver reported for each row before or despite the
     * failure, so rows that were applied are not lost from view.
     */
    record Failed(String sqlState, int errorCode, String message, List<Long> counts) implements Outcome {
        public Failed {
            counts = List.copyOf(counts);
        }

        public Failed(String sqlState, int errorCode, String message) {
            this(sqlState, errorCode, message, List.of());
        }
    }

    static Outcome failed(Throwable thrown) {
        if (thrown instanceof BatchUpdateException e) {
            long[] counts = e.getLargeUpdateCounts() == null ? new long[0] : e.getLargeUpdateCounts();
            return new Failed(e.getSQLState(), e.getErrorCode(), e.getMessage(),
                    Arrays.stream(counts).boxed().toList());
        }
        if (thrown instanceof SQLException e) {
            return new Failed(e.getSQLState(), e.getErrorCode(), e.getMessage());
        }
        return new Failed(null, 0, thrown.toString());
    }
}

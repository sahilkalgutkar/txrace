package com.sahilkalgutkar.txrace.schedule;

import com.sahilkalgutkar.txrace.trace.Trace;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A schedule could not be followed: it named a transaction that had already finished, ran out
 * while one still had steps to take, or a step never finished. The message ends with everything
 * that ran before the scheduler gave up.
 */
public final class ScheduleException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Trace trace;
    private final transient List<Result> results;

    ScheduleException(String reason, Trace trace, List<Result> results) {
        super(reason + "\n\nWhat ran:\n" + trace.render());
        this.trace = trace;
        this.results = Collections.unmodifiableList(new ArrayList<>(results));
        // A transaction that threw is usually why the schedule stopped fitting, so its exception
        // travels with this one.
        for (Result result : results) {
            if (result instanceof Result.RolledBack rolledBack) {
                addSuppressed(rolledBack.cause());
            }
        }
    }

    public Trace trace() {
        return trace;
    }

    /** How each transaction ended, or null for one that had not finished when the run stopped. */
    public List<Result> results() {
        return results;
    }
}

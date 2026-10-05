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
    private final boolean unrepeatable;

    ScheduleException(String reason, Trace trace, List<Result> results, boolean unrepeatable) {
        super(reason + "\n\nWhat ran:\n" + trace.render());
        this.trace = trace;
        this.unrepeatable = unrepeatable;
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

    /**
     * True when the schedule was refused because the database, not the schedule, would have
     * decided what happened next, so the same schedule could end more than one way.
     */
    public boolean unrepeatable() {
        return unrepeatable;
    }

    /** How each transaction ended, or null for one that had not finished when the run stopped. */
    public List<Result> results() {
        return results;
    }
}

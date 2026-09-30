package com.sahilkalgutkar.txrace.schedule;

import com.sahilkalgutkar.txrace.trace.Trace;
import java.io.Serial;

/**
 * A schedule could not be followed: it named a transaction that had already finished, ran out
 * while one still had steps to take, or a step never finished. The message ends with everything
 * that ran before the scheduler gave up.
 */
public final class ScheduleException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Trace trace;

    ScheduleException(String reason, Trace trace) {
        super(reason + "\n\nWhat ran:\n" + trace.render());
        this.trace = trace;
    }

    public Trace trace() {
        return trace;
    }
}

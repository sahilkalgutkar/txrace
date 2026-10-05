package com.sahilkalgutkar.txrace.schedule;

import java.io.Serial;

/** Why the scheduler stopped following a schedule. It becomes the message of a {@link ScheduleException}. */
final class Stop extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean unrepeatable;

    Stop(String message) {
        this(message, false);
    }

    /** {@code unrepeatable} when the database, not the schedule, decided how the run went on. */
    Stop(String message, boolean unrepeatable) {
        super(message, null, false, false);
        this.unrepeatable = unrepeatable;
    }

    boolean unrepeatable() {
        return unrepeatable;
    }
}

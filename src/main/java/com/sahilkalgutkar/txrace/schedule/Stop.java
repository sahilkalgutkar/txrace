package com.sahilkalgutkar.txrace.schedule;

import java.io.Serial;

/** Why the scheduler stopped following a schedule. It becomes the message of a {@link ScheduleException}. */
final class Stop extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    Stop(String message) {
        super(message, null, false, false);
    }
}

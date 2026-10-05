package com.sahilkalgutkar.txrace.schedule;

import java.util.List;

/**
 * Everything an {@link Explorer} ran. {@code complete} is false when it stopped at its limit with
 * orders still to run.
 */
public record Exploration(List<Explored> runs, List<Refused> refused, boolean complete) {

    /** One order run to the end, with whatever was observed afterwards. */
    public record Explored(Run run, Object state) {}

    /**
     * An order the scheduler refused because the database, not the schedule, would have decided
     * what came next. {@code schedule} holds the steps up to that point.
     */
    public record Refused(Schedule schedule, ScheduleException reason) {}

    public Exploration {
        runs = List.copyOf(runs);
        refused = List.copyOf(refused);
    }
}

package com.sahilkalgutkar.txrace.schedule;

import java.util.List;

/**
 * Everything an {@link Explorer} ran. {@code complete} is false when it stopped at its limit with
 * orders still to run.
 */
public record Exploration(List<Explored> runs, List<Refused> refused, boolean complete) {

    /**
     * One order run to the end, with whatever was observed afterwards, and how many times it
     * switched away from a transaction that could have gone on.
     */
    public record Explored(Run run, Object state, int preemptions) {}

    /**
     * An order that could not be run repeatably: the scheduler refused it because the database,
     * not the schedule, would have decided what came next, or replaying its prefix did not offer
     * the same transactions as the run it branched from. {@code schedule} holds the steps up to
     * that point.
     */
    public record Refused(Schedule schedule, String reason) {}

    public Exploration {
        runs = List.copyOf(runs);
        refused = List.copyOf(refused);
    }
}

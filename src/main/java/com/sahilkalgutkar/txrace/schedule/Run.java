package com.sahilkalgutkar.txrace.schedule;

import com.sahilkalgutkar.txrace.trace.Trace;
import java.util.List;

/** What running a schedule produced: every step in the order it ran, and how each transaction ended. */
public record Run(Schedule schedule, Trace trace, List<Result> results) {

    public Run {
        results = List.copyOf(results);
    }

    /** The result of transaction n, counting from 1. */
    public Result result(int transaction) {
        return results.get(transaction - 1);
    }
}

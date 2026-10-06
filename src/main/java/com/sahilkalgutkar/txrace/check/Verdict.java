package com.sahilkalgutkar.txrace.check;

import com.sahilkalgutkar.txrace.schedule.Exploration;
import com.sahilkalgutkar.txrace.trace.Columns;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * What a {@link Checker} found. {@code violations} are the orders that ended in a way no serial
 * order does, the one with the fewest preemptions first.
 */
public record Verdict(int transactions, List<Serial> serial, Exploration explored,
        List<Exploration.Explored> violations) {

    /** How running some of the transactions one after another, in {@code order}, ended. */
    public record Serial(List<Integer> order, Ending ending) {}

    private static final int WIDTH = 44;

    public Verdict {
        serial = List.copyOf(serial);
        violations = List.copyOf(violations);
    }

    /** True when every order ran, and each ended the way some serial order does. */
    public boolean holds() {
        return violations.isEmpty() && explored.complete();
    }

    /** The failing order with the fewest preemptions. */
    public Optional<Exploration.Explored> counterexample() {
        return violations.stream().findFirst();
    }

    public String report() {
        StringBuilder out = new StringBuilder();
        out.append(transactions).append(" transactions, ").append(explored.runs().size()).append(" orders run");
        if (!explored.refused().isEmpty()) {
            out.append(", ").append(explored.refused().size()).append(" refused as unrepeatable and not judged");
        }
        if (!explored.complete()) {
            out.append(", and the search stopped at its limit before running them all");
        }
        out.append(".\n");
        if (violations.isEmpty()) {
            out.append(serial.isEmpty() ? "Every order kept the invariant.\n"
                    : "Every order ended the way some serial order does.\n");
            return out.toString();
        }
        Exploration.Explored shortest = violations.getFirst();
        out.append(violations.size()).append(violations.size() == 1 ? " order ends" : " orders end")
                .append(serial.isEmpty() ? " breaking the invariant" : " in a way no serial order does")
                .append(". The one with the fewest preemptions (")
                .append(shortest.preemptions()).append(") is ").append(shortest.run().schedule()).append(":\n\n");
        out.append(Columns.render(shortest.run().trace(), transactions, WIDTH)).append('\n');
        out.append("It ended with ")
                .append(Ending.of(shortest, IntStream.rangeClosed(1, transactions).boxed().toList()).describe())
                .append(".\n");
        if (!serial.isEmpty()) {
            out.append("Run one after another, they end with:\n");
        }
        for (Serial run : serial) {
            if (run.order().size() == transactions) {
                out.append("  ").append(run.order().stream().map(String::valueOf).collect(Collectors.joining(" then ")))
                        .append(": ").append(run.ending().describe()).append('\n');
            }
        }
        return out.toString();
    }
}

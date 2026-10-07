package com.sahilkalgutkar.txrace.check;

import com.sahilkalgutkar.txrace.schedule.Exploration;
import com.sahilkalgutkar.txrace.trace.Columns;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * What a {@link Checker} found. {@code violations} are the orders that ended in a way no serial
 * order does, the one with the fewest preemptions first. {@code returnValues} is false when the
 * check ignored what the transactions returned.
 */
public record Verdict(int transactions, List<Serial> serial, Exploration explored,
        List<Exploration.Explored> violations, boolean returnValues) {

    /** How running some of the transactions one after another, in {@code order}, ended. */
    public record Serial(List<Integer> order, Ending ending) {}

    private static final int WIDTH = 44;

    private String refusedIn(List<Integer> finished) {
        List<String> refused = IntStream.rangeClosed(1, transactions).filter(n -> !finished.contains(n))
                .mapToObj(String::valueOf).toList();
        return (refused.size() == 1 ? "transaction " : "transactions ") + String.join(" and ", refused);
    }

    public Verdict {
        serial = List.copyOf(serial);
        violations = List.copyOf(violations);
    }

    /**
     * True when every order ran and each ended the way some serial order does. An order refused
     * as unrepeatable was never judged, and neither was anything past a limit, so either means
     * this cannot be said.
     */
    public boolean holds() {
        return violations.isEmpty() && explored.complete() && explored.refused().isEmpty();
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
            String kept = serial.isEmpty() ? "kept the invariant" : "ended the way some serial order does";
            out.append(holds() ? "Every order " + kept + ".\n"
                    : "Every order that ran " + kept + ", but not every order ran, so that is all this shows.\n");
            return out.toString();
        }
        Exploration.Explored shortest = violations.getFirst();
        out.append(violations.size()).append(violations.size() == 1 ? " order ends" : " orders end")
                .append(serial.isEmpty() ? " breaking the invariant" : " in a way no serial order does")
                .append(". The one with the fewest preemptions (")
                .append(shortest.preemptions()).append(") is ").append(shortest.run().schedule()).append(":\n\n");
        out.append(Columns.render(shortest.run().trace(), transactions, WIDTH)).append('\n');
        Ending ending = Ending.of(shortest, IntStream.rangeClosed(1, transactions).boxed().toList());
        out.append("It ended with ").append(ending.describe(returnValues)).append(".\n");
        if (serial.isEmpty()) {
            return out.toString();
        }
        // It was compared with serial runs of the transactions the database did not refuse.
        List<Integer> finished = ending.finished();
        if (finished.size() == transactions) {
            out.append("Run one after another, they end with:\n");
        } else {
            out.append("The database refused ").append(refusedIn(finished))
                    .append(", so it is compared with the rest run one after another:\n");
        }
        for (Serial run : serial) {
            if (run.order().stream().sorted().toList().equals(finished)) {
                out.append("  ").append(run.order().isEmpty() ? "none of them"
                                : run.order().stream().map(String::valueOf).collect(Collectors.joining(" then ")))
                        .append(": ").append(run.ending().describe(returnValues)).append('\n');
            }
        }
        return out.toString();
    }
}

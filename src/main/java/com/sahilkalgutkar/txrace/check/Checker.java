package com.sahilkalgutkar.txrace.check;

import com.sahilkalgutkar.txrace.schedule.Exploration;
import com.sahilkalgutkar.txrace.schedule.Explorer;
import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Checks that every order the transactions can run in ends the way some serial order would.
 *
 * <p>No invariant is needed: the serial orders are the reference. A transaction the database
 * rolled back left nothing behind, so a run is compared against serial runs of just the
 * transactions that committed in it. That makes a refusal such as a serialization failure a
 * correct ending rather than a finding.
 */
public final class Checker {

    private final Explorer explorer;

    /** {@code explorer} sets the starting state, what is observed, and how far to search. */
    public Checker(Explorer explorer) {
        this.explorer = Objects.requireNonNull(explorer, "explorer");
    }

    public Verdict check(Transaction... transactions) throws SQLException {
        return check(List.of(transactions));
    }

    public Verdict check(List<Transaction> transactions) throws SQLException {
        int count = transactions.size();
        List<Verdict.Serial> serial = new ArrayList<>();
        // Every serial order of every set of transactions that could be the ones that commit,
        // the empty set included: 1 + 2 + 2 = 5 runs for two transactions, 16 for three.
        for (int set = 0; set < 1 << count; set++) {
            List<Integer> numbers = new ArrayList<>();
            for (int n = 1; n <= count; n++) {
                if ((set & 1 << (n - 1)) != 0) {
                    numbers.add(n);
                }
            }
            Exploration runs = explorer.withPreemptions(0)
                    .explore(numbers.stream().map(n -> transactions.get(n - 1)).toList());
            for (Exploration.Explored run : runs.runs()) {
                List<Integer> order = run.run().schedule().order().stream().distinct()
                        .map(i -> numbers.get(i - 1)).toList();
                serial.add(new Verdict.Serial(order, Ending.of(run, numbers)));
            }
        }
        Set<Ending> allowed = serial.stream().map(Verdict.Serial::ending).collect(Collectors.toSet());
        List<Integer> everyone = IntStream.rangeClosed(1, count).boxed().toList();

        Exploration explored = explorer.explore(transactions);
        List<Exploration.Explored> violations = explored.runs().stream()
                .filter(run -> !allowed.contains(Ending.of(run, everyone)))
                .sorted(Comparator.comparingInt(Exploration.Explored::preemptions)
                        .thenComparingInt(run -> run.run().schedule().size())
                        .thenComparing(run -> run.run().schedule().toString()))
                .toList();
        return new Verdict(count, serial, explored, violations);
    }
}

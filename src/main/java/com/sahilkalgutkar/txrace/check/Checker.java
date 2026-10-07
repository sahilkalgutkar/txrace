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
import java.util.stream.Stream;

/**
 * Checks that every order the transactions can run in ends the way some serial order would.
 *
 * <p>No invariant is needed: the serial orders are the reference. A transaction the database
 * refused left nothing behind, so a run is compared against serial runs of just the transactions
 * that were not refused in it. That makes a refusal such as a serialization failure a correct
 * ending rather than a finding. A transaction that gave up by its own logic is not excused: see
 * {@link Ending}.
 *
 * <p>Sequences and identity columns move on even when a transaction rolls back, so a generated key
 * in what is observed or returned shows the gap a refused transaction left, and the comparison
 * fails. Leave them out.
 */
public final class Checker {

    private final Explorer explorer;
    private final boolean returnValues;
    private final Invariant invariant;

    /** {@code explorer} sets the starting state, what is observed, and how far to search. */
    public Checker(Explorer explorer) {
        this(explorer, true, null);
    }

    private Checker(Explorer explorer, boolean returnValues, Invariant invariant) {
        this.explorer = Objects.requireNonNull(explorer, "explorer");
        this.returnValues = returnValues;
        this.invariant = invariant;
    }

    /**
     * Compares only which transactions committed and what was observed. For transactions that
     * return something different on every run, such as a fresh token, which no serial run could
     * match.
     */
    public Checker ignoringReturnValues() {
        return new Checker(explorer, false, invariant);
    }

    /**
     * Judges each ending by {@code invariant} instead of by the serial orders, which are then not
     * run at all.
     */
    public Checker judgingBy(Invariant invariant) {
        return new Checker(explorer, returnValues, Objects.requireNonNull(invariant, "invariant"));
    }

    private Ending judged(Ending ending) {
        return returnValues ? ending : ending.withoutReturnValues();
    }

    public Verdict check(Transaction... transactions) throws SQLException {
        return check(List.of(transactions));
    }

    public Verdict check(List<Transaction> transactions) throws SQLException {
        int count = transactions.size();
        List<Integer> everyone = IntStream.rangeClosed(1, count).boxed().toList();
        if (invariant != null) {
            Exploration explored = explorer.explore(transactions);
            return new Verdict(count, List.of(), explored, order(explored.runs().stream()
                    .filter(run -> !invariant.holds(judged(Ending.of(run, everyone))))), returnValues);
        }
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
        Set<Ending> allowed = serial.stream().map(run -> judged(run.ending())).collect(Collectors.toSet());

        Exploration explored = explorer.explore(transactions);
        // An order without preemptions is one of the serial orders run again, so it has to end the
        // way it did then. If it does not, the endings hold something that changes on every run,
        // and every order would be reported for it.
        for (Exploration.Explored run : explored.runs()) {
            Ending ending = judged(Ending.of(run, everyone));
            if (run.preemptions() == 0 && !allowed.contains(ending)) {
                throw new IllegalStateException("the serial order " + run.run().schedule() + " ended with "
                        + ending.describe(returnValues) + " when it ran again, which no serial run did."
                        + " Something in what the transactions return or what is observed changes on every run,"
                        + " a token or a timestamp say: leave it out, or use ignoringReturnValues()");
            }
        }
        return new Verdict(count, serial, explored, order(explored.runs().stream()
                .filter(run -> !allowed.contains(judged(Ending.of(run, everyone))))), returnValues);
    }

    /** Fewest preemptions first, then the shortest, then by schedule, so the report is the same every time. */
    private static List<Exploration.Explored> order(Stream<Exploration.Explored> violations) {
        return violations.sorted(Comparator.comparingInt(Exploration.Explored::preemptions)
                        .thenComparingInt(run -> run.run().schedule().size())
                        .thenComparing(run -> run.run().schedule().toString()))
                .toList();
    }
}

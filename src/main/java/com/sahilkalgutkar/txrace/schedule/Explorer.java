package com.sahilkalgutkar.txrace.schedule;

import java.io.Serial;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Runs transactions in every order their steps can take, one run per order, each from the same
 * starting state.
 *
 * <p>The orders are not known up front, because a transaction can branch on what it reads and a
 * step can wait on a lock. So I search them the way the CHESS paper does, without storing any
 * state: run once, recording which transactions were ready at each step, then for every ready
 * transaction that was not picked, run again with the same choices up to there and that one next,
 * carrying on from there by the default rule. Each order is run exactly once.
 *
 * <p>The number of orders grows fast, so the search can be bounded by preemptions: switching away
 * from a transaction that could have taken another step. The default rule never preempts, so
 * every run stays within the bound its prefix set. Most concurrency bugs need only one or two
 * preemptions to show, which is what makes a small bound worth running.
 */
public final class Explorer {

    /** Puts the database into the state every run starts from. */
    @FunctionalInterface
    public interface Setup {
        void prepare(DataSource database) throws SQLException;
    }

    /** Reads whatever a run should be judged by, once it has finished. */
    @FunctionalInterface
    public interface Observation {
        Object observe(DataSource database) throws SQLException;
    }

    private final DataSource dataSource;
    private final Setup setup;
    private final Duration timeout;
    private final Observation observation;
    private final int preemptions;
    private final int limit;

    public Explorer(DataSource dataSource, Setup setup) {
        this(dataSource, setup, Duration.ofSeconds(5), database -> null, Integer.MAX_VALUE, 10_000);
    }

    private Explorer(DataSource dataSource, Setup setup, Duration timeout, Observation observation, int preemptions,
            int limit) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.setup = Objects.requireNonNull(setup, "setup");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.preemptions = preemptions;
        this.limit = limit;
    }

    /** Reads {@code observation} after every run and keeps it with the run. */
    public Explorer observing(Observation observation) {
        return new Explorer(dataSource, setup, timeout, observation, preemptions, limit);
    }

    /** Runs only orders with at most {@code bound} preemptions. */
    public Explorer withPreemptions(int bound) {
        if (bound < 0) {
            throw new IllegalArgumentException("the bound can't be negative, got " + bound);
        }
        return new Explorer(dataSource, setup, timeout, observation, bound, limit);
    }

    /** Stops after {@code runs} runs, and says the exploration is incomplete if any orders were left. */
    public Explorer limitedTo(int runs) {
        if (runs < 1) {
            throw new IllegalArgumentException("the limit has to be at least one run, got " + runs);
        }
        return new Explorer(dataSource, setup, timeout, observation, preemptions, runs);
    }

    /** The scheduler's timeout for each run. */
    public Explorer withTimeout(Duration timeout) {
        return new Explorer(dataSource, setup, timeout, observation, preemptions, limit);
    }

    public Exploration explore(Transaction... transactions) throws SQLException {
        return explore(List.of(transactions));
    }

    public Exploration explore(List<Transaction> transactions) throws SQLException {
        Scheduler scheduler = new Scheduler(dataSource, timeout);
        List<Exploration.Explored> runs = new ArrayList<>();
        List<Exploration.Refused> refused = new ArrayList<>();
        Deque<Branch> pending = new ArrayDeque<>();
        pending.push(new Branch(List.of(), List.of()));
        while (!pending.isEmpty()) {
            if (runs.size() + refused.size() >= limit) {
                return new Exploration(runs, refused, false);
            }
            Branch branch = pending.pop();
            List<Integer> prefix = branch.prefix();
            setup.prepare(dataSource);
            Recorder recorder = new Recorder(prefix, branch.offered());
            try {
                Run run = scheduler.run(recorder, transactions);
                runs.add(new Exploration.Explored(run, observation.observe(dataSource)));
            } catch (ScheduleException e) {
                // An order the database decided rather than the schedule is a finding, not a failure.
                if (!e.unrepeatable()) {
                    throw e;
                }
                refused.add(new Exploration.Refused(new Schedule(recorder.taken), firstLine(e.getMessage())));
            } catch (Diverged e) {
                refused.add(new Exploration.Refused(new Schedule(recorder.taken), e.getMessage()));
            }
            branch(prefix, recorder, pending);
        }
        return new Exploration(runs, refused, true);
    }

    /**
     * Queues every order that differs from the one just run at some step after its prefix. Earlier
     * steps were branched on by the runs that led here, so no order is queued twice.
     */
    private void branch(List<Integer> prefix, Recorder recorder, Deque<Branch> pending) {
        for (int i = prefix.size(); i < recorder.taken.size(); i++) {
            for (int other : recorder.offered.get(i)) {
                if (other == recorder.taken.get(i)) {
                    continue;
                }
                List<Integer> next = new ArrayList<>(recorder.taken.subList(0, i));
                next.add(other);
                if (preemptions(next, recorder.offered) <= preemptions) {
                    pending.push(new Branch(next, List.copyOf(recorder.offered.subList(0, i + 1))));
                }
            }
        }
    }

    /** An order still to run: its prefix, and what each step of it was offered when it was found. */
    private record Branch(List<Integer> prefix, List<Set<Integer>> offered) {}

    /**
     * How many times {@code order} switches away from a transaction that was still ready. The
     * ready sets come from the run the order branched off, which took the same steps up to its end.
     */
    static int preemptions(List<Integer> order, List<Set<Integer>> offered) {
        int count = 0;
        for (int i = 1; i < order.size(); i++) {
            if (!order.get(i).equals(order.get(i - 1)) && offered.get(i).contains(order.get(i - 1))) {
                count++;
            }
        }
        return count;
    }

    private static String firstLine(String message) {
        return message.lines().findFirst().orElse(message);
    }

    /** A replay that did not offer what the run it branched from was offered. */
    private static final class Diverged extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        Diverged(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Picks each step: the prefix first, then whichever transaction took the last step if it is
     * still ready, otherwise the lowest-numbered ready one.
     */
    private static final class Recorder implements Chooser {
        private final List<Integer> prefix;
        private final List<Set<Integer>> expected;
        private final List<Integer> taken = new ArrayList<>();
        private final List<Set<Integer>> offered = new ArrayList<>();

        /** {@code expected} holds what the run this one branched from was offered at each step. */
        Recorder(List<Integer> prefix, List<Set<Integer>> expected) {
            this.prefix = prefix;
            this.expected = expected;
        }

        @Override
        public int next(List<Integer> soFar, Set<Integer> ready) {
            offered.add(ready);
            int position = soFar.size();
            int choice;
            if (position < prefix.size()) {
                choice = prefix.get(position);
                // Anything else would make the orders branched from here wrong, or unreachable.
                if (!ready.equals(expected.get(position))) {
                    throw new Diverged("replaying " + new Schedule(prefix) + ", position " + (position + 1)
                            + " offered " + ready + " where the run it branched from was offered "
                            + expected.get(position));
                }
            } else {
                int last = soFar.isEmpty() ? 0 : soFar.getLast();
                choice = ready.contains(last) ? last : Collections.min(ready);
            }
            taken.add(choice);
            return choice;
        }
    }
}

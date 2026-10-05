package com.sahilkalgutkar.txrace.schedule;

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
    private final int limit;

    public Explorer(DataSource dataSource, Setup setup) {
        this(dataSource, setup, Duration.ofSeconds(5), database -> null, 10_000);
    }

    private Explorer(DataSource dataSource, Setup setup, Duration timeout, Observation observation, int limit) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.setup = Objects.requireNonNull(setup, "setup");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.limit = limit;
    }

    /** Reads {@code observation} after every run and keeps it with the run. */
    public Explorer observing(Observation observation) {
        return new Explorer(dataSource, setup, timeout, observation, limit);
    }

    /** Stops after {@code runs} runs, and says the exploration is incomplete if any orders were left. */
    public Explorer limitedTo(int runs) {
        if (runs < 1) {
            throw new IllegalArgumentException("the limit has to be at least one run, got " + runs);
        }
        return new Explorer(dataSource, setup, timeout, observation, runs);
    }

    /** The scheduler's timeout for each run. */
    public Explorer withTimeout(Duration timeout) {
        return new Explorer(dataSource, setup, timeout, observation, limit);
    }

    public Exploration explore(Transaction... transactions) throws SQLException {
        return explore(List.of(transactions));
    }

    public Exploration explore(List<Transaction> transactions) throws SQLException {
        Scheduler scheduler = new Scheduler(dataSource, timeout);
        List<Exploration.Explored> runs = new ArrayList<>();
        List<Exploration.Refused> refused = new ArrayList<>();
        Deque<List<Integer>> pending = new ArrayDeque<>();
        pending.push(List.of());
        while (!pending.isEmpty()) {
            if (runs.size() + refused.size() >= limit) {
                return new Exploration(runs, refused, false);
            }
            List<Integer> prefix = pending.pop();
            setup.prepare(dataSource);
            Recorder recorder = new Recorder(prefix);
            try {
                Run run = scheduler.run(recorder, transactions);
                runs.add(new Exploration.Explored(run, observation.observe(dataSource)));
            } catch (ScheduleException e) {
                // An order the database decided rather than the schedule is a finding, not a failure.
                if (!e.unrepeatable()) {
                    throw e;
                }
                refused.add(new Exploration.Refused(new Schedule(recorder.taken), e));
            }
            branch(prefix, recorder, pending);
        }
        return new Exploration(runs, refused, true);
    }

    /**
     * Queues every order that differs from the one just run at some step after its prefix. Earlier
     * steps were branched on by the runs that led here, so no order is queued twice.
     */
    private void branch(List<Integer> prefix, Recorder recorder, Deque<List<Integer>> pending) {
        for (int i = prefix.size(); i < recorder.taken.size(); i++) {
            for (int other : recorder.offered.get(i)) {
                if (other == recorder.taken.get(i)) {
                    continue;
                }
                List<Integer> next = new ArrayList<>(recorder.taken.subList(0, i));
                next.add(other);
                pending.push(next);
            }
        }
    }

    /**
     * Picks each step: the prefix first, then whichever transaction took the last step if it is
     * still ready, otherwise the lowest-numbered ready one.
     */
    private static final class Recorder implements Chooser {
        private final List<Integer> prefix;
        private final List<Integer> taken = new ArrayList<>();
        private final List<Set<Integer>> offered = new ArrayList<>();

        Recorder(List<Integer> prefix) {
            this.prefix = prefix;
        }

        @Override
        public int next(List<Integer> soFar, Set<Integer> ready) {
            offered.add(ready);
            int position = soFar.size();
            int choice;
            if (position < prefix.size()) {
                choice = prefix.get(position);
                if (!ready.contains(choice)) {
                    throw new IllegalStateException("replaying " + new Schedule(prefix) + ", transaction " + choice
                            + " was not ready at position " + (position + 1) + ": the run did not repeat itself");
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

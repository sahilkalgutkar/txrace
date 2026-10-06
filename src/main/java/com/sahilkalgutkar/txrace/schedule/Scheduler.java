package com.sahilkalgutkar.txrace.schedule;

import com.sahilkalgutkar.txrace.jdbc.TracingDataSource;
import com.sahilkalgutkar.txrace.trace.Step;
import java.io.Serial;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;

/**
 * Runs transactions concurrently, one step at a time, in exactly the order a {@link Schedule}
 * gives.
 *
 * <p>Each transaction gets its own thread and connection. JDBC calls block, so a transaction
 * cannot be paused part-way through one on a shared thread; it has to be held back before the
 * call, on a thread of its own, which is what the {@link Turnstile} does.
 */
public final class Scheduler {

    private final DataSource dataSource;
    private final Duration timeout;
    private final long timeoutNanos;

    public Scheduler(DataSource dataSource) {
        this(dataSource, Duration.ofSeconds(5));
    }

    /**
     * {@code timeout} bounds how long a released step may take, and how long a transaction may
     * spend between steps. A step waiting on a lock held by a transaction the schedule has not
     * released yet never finishes on its own. For now it is reported once the timeout runs out,
     * unless the database's own lock timeout fires first, in which case the step shows up in the
     * trace as failed.
     */
    public Scheduler(DataSource dataSource, Duration timeout) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("the timeout has to be positive, got " + timeout);
        }
        // A timeout too long to count in nanoseconds means no timeout, not an overflow later on.
        this.timeoutNanos = timeout.compareTo(Duration.ofNanos(Long.MAX_VALUE)) >= 0
                ? Long.MAX_VALUE : timeout.toNanos();
    }

    public Run run(Schedule schedule, Transaction... transactions) throws SQLException {
        return run(schedule, List.of(transactions));
    }

    public Run run(Schedule schedule, List<Transaction> transactions) throws SQLException {
        // Checked before anything is opened or started, so a bad argument leaves nothing behind.
        Objects.requireNonNull(schedule, "schedule");
        return run(follower -> {
            follower.follow(schedule);
            return schedule;
        }, transactions);
    }

    /** Runs the transactions, letting {@code chooser} pick each step. The run's schedule is the one it picked. */
    Run run(Chooser chooser, List<Transaction> transactions) throws SQLException {
        Objects.requireNonNull(chooser, "chooser");
        return run(follower -> new Schedule(follower.choose(chooser)), transactions);
    }

    /** How a run decides its steps: by following a schedule, or by choosing as it goes. */
    private interface Plan {
        Schedule carryOut(Follower follower) throws Stop, InterruptedException, SQLException;
    }

    private Run run(Plan plan, List<Transaction> transactions) throws SQLException {
        transactions.forEach(transaction -> Objects.requireNonNull(transaction, "transaction"));
        Turnstile turnstile = new Turnstile();
        TracingDataSource traced = new TracingDataSource(dataSource, turnstile);
        try (LockWatch watch = LockWatch.open(dataSource)) {
            return run(plan, transactions, turnstile, traced, watch);
        }
    }

    private Run run(Plan plan, List<Transaction> transactions, Turnstile turnstile, TracingDataSource traced,
            LockWatch watch) throws SQLException {
        int count = transactions.size();
        // Opened here and in order, so that connection n in the trace is transaction n.
        List<Connection> connections = open(traced, watch, count);
        Result[] results = new Result[count];
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = i + 1;
            Transaction transaction = transactions.get(i);
            Connection connection = connections.get(i);
            workers.add(Thread.ofPlatform().name("txrace-t" + n).daemon().start(() -> {
                try {
                    results[n - 1] = runOne(transaction, connection);
                } finally {
                    turnstile.finish(n);
                }
            }));
        }

        String failure = null;
        boolean unrepeatable = false;
        RuntimeException unexpected = null;
        Schedule followed = null;
        try {
            followed = plan.carryOut(new Follower(count, turnstile, watch, traced.trace(), results, timeout,
                    timeoutNanos));
        } catch (Stop stop) {
            failure = stop.getMessage();
            unrepeatable = stop.unrepeatable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = "interrupted while running the schedule";
        } catch (SQLException e) {
            failure = "could not ask the database about locks: " + e;
        } catch (RuntimeException e) {
            unexpected = e;
        }
        if (failure != null || unexpected != null) {
            turnstile.abort();
        }
        List<String> stuck = join(workers);
        if (unexpected != null) {
            throw unexpected;
        }
        if (!stuck.isEmpty()) {
            failure = (failure == null ? "" : failure + ". ") + "Still running after the schedule was abandoned: "
                    + String.join(", ", stuck);
        }
        if (failure != null) {
            // A copy, because a transaction the join gave up on may still add steps.
            throw new ScheduleException(failure, traced.trace().copy(), Arrays.asList(results), unrepeatable);
        }
        return new Run(followed, traced.trace(), List.of(results));
    }

    private static List<Connection> open(TracingDataSource traced, LockWatch watch, int count) throws SQLException {
        List<Connection> connections = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                Connection connection = traced.getConnection();
                connections.add(connection);
                // With autocommit on, so the question does not start a transaction. A pool can
                // hand out connections with it off, and nothing has been sent yet, so turning it
                // on commits nothing.
                connection.setAutoCommit(true);
                watch.register(i + 1, TracingDataSource.unwrapped(connection));
                connection.setAutoCommit(false);
            }
        } catch (SQLException e) {
            for (Connection connection : connections) {
                try {
                    connection.close();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
        return connections;
    }

    private static Result runOne(Transaction transaction, Connection connection) {
        try {
            Object value = transaction.run(connection);
            connection.commit();
            return new Result.Committed(value);
        } catch (Throwable e) {
            try {
                connection.rollback();
            } catch (Throwable suppressed) {
                e.addSuppressed(suppressed);
            }
            return new Result.RolledBack(e);
        } finally {
            try {
                // Nothing is pending by now, so closing sends no step.
                connection.close();
            } catch (SQLException | RuntimeException ignored) {
                // The outcome is already decided; a failed close changes nothing about it.
            }
        }
    }

    private long deadline() {
        // Overflows for a very long timeout, which is fine: deadlines are only ever compared by
        // subtracting nanoTime, and that wraps back round.
        return System.nanoTime() + timeoutNanos;
    }

    /**
     * Waits for the workers to finish, for up to one timeout in total. It keeps waiting through an
     * interrupt, because returning early would leave rollbacks still running and locks still held,
     * and sets the interrupt again before returning.
     */
    private List<String> join(List<Thread> workers) {
        boolean interrupted = Thread.interrupted();
        long deadline = deadline();
        for (Thread worker : workers) {
            long left;
            while (worker.isAlive() && (left = deadline - System.nanoTime()) > 0) {
                try {
                    worker.join(Duration.ofNanos(left));
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return workers.stream().filter(Thread::isAlive).map(Thread::getName).toList();
    }
}

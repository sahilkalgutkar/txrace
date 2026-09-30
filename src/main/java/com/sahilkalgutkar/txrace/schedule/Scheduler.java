package com.sahilkalgutkar.txrace.schedule;

import com.sahilkalgutkar.txrace.jdbc.TracingDataSource;
import com.sahilkalgutkar.txrace.trace.Step;
import java.io.Serial;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
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
    }

    public Run run(Schedule schedule, Transaction... transactions) throws SQLException {
        return run(schedule, List.of(transactions));
    }

    public Run run(Schedule schedule, List<Transaction> transactions) throws SQLException {
        Turnstile turnstile = new Turnstile();
        TracingDataSource traced = new TracingDataSource(dataSource, turnstile);
        int count = transactions.size();
        // Opened here and in order, so that connection n in the trace is transaction n.
        List<Connection> connections = open(traced, count);
        Result[] results = new Result[count];
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int n = i + 1;
            Transaction transaction = transactions.get(i);
            Connection connection = connections.get(i);
            workers.add(Thread.ofPlatform().name("txrace-t" + n).daemon().start(() -> {
                results[n - 1] = runOne(transaction, connection);
                turnstile.finished(n);
            }));
        }

        String failure;
        try {
            follow(schedule, count, turnstile);
            failure = null;
        } catch (Stop stop) {
            failure = stop.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = "interrupted while running the schedule";
        }
        if (failure != null) {
            turnstile.abort();
        }
        List<String> stuck = join(workers);
        if (!stuck.isEmpty()) {
            failure = (failure == null ? "" : failure + ". ") + "Still running after the schedule was abandoned: "
                    + String.join(", ", stuck);
        }
        if (failure != null) {
            throw new ScheduleException(failure, traced.trace());
        }
        return new Run(schedule, traced.trace(), List.of(results));
    }

    private static List<Connection> open(TracingDataSource traced, int count) throws SQLException {
        List<Connection> connections = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                Connection connection = traced.getConnection();
                connections.add(connection);
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
        Result result;
        try {
            Object value = transaction.run(connection);
            connection.commit();
            result = new Result.Committed(value);
        } catch (Throwable e) {
            try {
                connection.rollback();
            } catch (SQLException suppressed) {
                e.addSuppressed(suppressed);
            }
            result = new Result.RolledBack(e);
        }
        try {
            // Nothing is pending by now, so closing sends no step.
            connection.close();
        } catch (SQLException ignored) {
            // The outcome is already decided; a failed close changes nothing about it.
        }
        return result;
    }

    private void follow(Schedule schedule, int count, Turnstile turnstile) throws Stop, InterruptedException {
        for (int i = 0; i < schedule.size(); i++) {
            int n = schedule.order().get(i);
            String at = "position " + (i + 1) + " of \"" + schedule + "\"";
            if (n > count) {
                throw new Stop(at + " names transaction " + n + ", but there are only " + count);
            }
            if (await(turnstile, n, at) == null) {
                throw new Stop(at + " asks for transaction " + n + ", which has already finished");
            }
            try {
                turnstile.release(n, deadline());
            } catch (TimeoutException e) {
                throw new Stop("the step transaction " + n + " took at " + at + " did not finish within " + timeout
                        + ". It may be waiting on a lock another transaction holds");
            }
        }
        for (int n = 1; n <= count; n++) {
            Step next = await(turnstile, n, "the end of the schedule");
            if (next != null) {
                throw new Stop("the schedule ran out while transaction " + n + " still had a step to take: "
                        + (next.sql() == null ? next.kind() : next.sql()));
            }
        }
    }

    private Step await(Turnstile turnstile, int n, String at) throws Stop, InterruptedException {
        try {
            return turnstile.await(n, deadline());
        } catch (TimeoutException e) {
            throw new Stop("transaction " + n + " neither took its next step nor finished within " + timeout
                    + " (at " + at + ")");
        }
    }

    private long deadline() {
        return System.nanoTime() + timeout.toNanos();
    }

    private List<String> join(List<Thread> workers) {
        List<String> stuck = new ArrayList<>();
        long deadline = deadline();
        for (Thread worker : workers) {
            try {
                worker.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (worker.isAlive()) {
                stuck.add(worker.getName());
            }
        }
        return stuck;
    }

    /** Why the scheduler stopped following the schedule. */
    private static final class Stop extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        Stop(String message) {
            super(message, null, false, false);
        }
    }
}

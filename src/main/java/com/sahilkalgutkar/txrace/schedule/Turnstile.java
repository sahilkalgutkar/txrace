package com.sahilkalgutkar.txrace.schedule;

import com.sahilkalgutkar.txrace.jdbc.Gate;
import com.sahilkalgutkar.txrace.trace.Outcome;
import com.sahilkalgutkar.txrace.trace.Step;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The gate every transaction's thread waits at. Each step parks until the scheduler admits that
 * transaction. The scheduler then waits for the step to finish, or finds it waiting on a lock and
 * moves on, in which case the step finishes later, during some other transaction's step.
 *
 * <p>Transactions are told apart by connection number, which is why the scheduler opens
 * connection n for transaction n.
 */
final class Turnstile implements Gate {

    private final Map<Integer, Step> waiting = new HashMap<>();
    private final Set<Integer> finished = new HashSet<>();
    private final Map<Integer, Integer> released = new HashMap<>();
    private final Map<Integer, Integer> completed = new HashMap<>();
    // Transactions with a database transaction open, and so possibly holding locks.
    private final Set<Integer> holding = new HashSet<>();
    private int admitted;
    private boolean aborted;

    @Override
    public synchronized void before(Step step) throws SQLException {
        int transaction = step.connection();
        waiting.put(transaction, step);
        notifyAll();
        try {
            while (admitted != transaction && !aborted) {
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted while waiting for its turn", e);
        } finally {
            waiting.remove(transaction);
        }
        if (admitted == transaction) {
            admitted = 0;
            return;
        }
        // Once a run is abandoned, rollbacks still go through so every transaction can let go
        // of its locks and finish. Anything else is refused.
        if (step.kind() != Step.Kind.ROLLBACK) {
            throw new SQLException("the schedule was abandoned");
        }
    }

    @Override
    public synchronized void after(Step step, Outcome outcome) {
        int transaction = step.connection();
        completed.merge(transaction, 1, Integer::sum);
        // SQLState class 40 means the database rolled the transaction back itself, as H2 does to a
        // deadlock victim, so nothing it held is held any more.
        boolean rolledBack = outcome instanceof Outcome.Failed failed
                && failed.sqlState() != null && failed.sqlState().startsWith("40");
        if (step.endsTransaction() || rolledBack) {
            holding.remove(transaction);
        } else {
            holding.add(transaction);
        }
        notifyAll();
    }

    /**
     * Waits until the transaction is parked at the gate or has finished. Returns the step it is
     * waiting to take, or null if it has finished.
     */
    synchronized Step await(int transaction, long deadline) throws InterruptedException, TimeoutException {
        while (!waiting.containsKey(transaction) && !finished.contains(transaction)) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new TimeoutException();
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return waiting.get(transaction);
    }

    /** Lets the transaction's waiting step through, without waiting for it to finish. */
    synchronized void release(int transaction) {
        admitted = transaction;
        released.merge(transaction, 1, Integer::sum);
        notifyAll();
    }

    /** Waits up to {@code nanos} for the transaction's released steps to finish, and says whether they have. */
    synchronized boolean awaitDone(int transaction, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        long left;
        while (!done(transaction) && (left = deadline - System.nanoTime()) > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return done(transaction);
    }

    /** Waits up to {@code nanos} for any step to finish or any transaction to park or finish. */
    synchronized void awaitAnything(long nanos) throws InterruptedException {
        TimeUnit.NANOSECONDS.timedWait(this, nanos);
    }

    synchronized boolean done(int transaction) {
        // At least, not exactly: after an abort, rollbacks go through without being released.
        return completed.getOrDefault(transaction, 0) >= released.getOrDefault(transaction, 0);
    }

    synchronized boolean holding(int transaction) {
        return holding.contains(transaction);
    }

    synchronized boolean finished(int transaction) {
        return finished.contains(transaction);
    }

    synchronized void finish(int transaction) {
        finished.add(transaction);
        notifyAll();
    }

    synchronized void abort() {
        aborted = true;
        admitted = 0;
        notifyAll();
    }
}

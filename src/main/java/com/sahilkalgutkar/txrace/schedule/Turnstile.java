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
 * transaction, and the scheduler waits for the step to finish before admitting the next one.
 *
 * <p>Transactions are told apart by connection number, which is why the scheduler opens
 * connection n for transaction n.
 */
final class Turnstile implements Gate {

    private final Map<Integer, Step> waiting = new HashMap<>();
    private final Set<Integer> finished = new HashSet<>();
    private int admitted;
    private long released;
    private long completed;
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
        completed++;
        notifyAll();
    }

    /**
     * Waits until the transaction is parked at the gate or has finished. Returns the step it is
     * waiting to take, or null if it has finished.
     */
    synchronized Step await(int transaction, long deadline) throws InterruptedException, TimeoutException {
        while (!waiting.containsKey(transaction) && !finished.contains(transaction)) {
            waitUntil(deadline);
        }
        return waiting.get(transaction);
    }

    /** Lets one waiting step through and waits for it to finish. */
    synchronized void release(int transaction, long deadline) throws InterruptedException, TimeoutException {
        admitted = transaction;
        released++;
        notifyAll();
        while (completed < released) {
            waitUntil(deadline);
        }
    }

    synchronized void finished(int transaction) {
        finished.add(transaction);
        notifyAll();
    }

    synchronized void abort() {
        aborted = true;
        admitted = 0;
        notifyAll();
    }

    private void waitUntil(long deadline) throws InterruptedException, TimeoutException {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new TimeoutException();
        }
        TimeUnit.NANOSECONDS.timedWait(this, left);
    }
}

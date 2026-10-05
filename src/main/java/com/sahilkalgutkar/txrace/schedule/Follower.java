package com.sahilkalgutkar.txrace.schedule;

import com.sahilkalgutkar.txrace.trace.Outcome;
import com.sahilkalgutkar.txrace.trace.Step;
import com.sahilkalgutkar.txrace.trace.Trace;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Follows one schedule through one run, step by step, or picks each step as it goes.
 *
 * <p>A released step either finishes, or the database says it is waiting on a lock another
 * transaction holds. Then the schedule moves on, and the step finishes during some later step,
 * usually the holder's commit or rollback. To keep a run deterministic, the follower lets every
 * such step settle after each later one: finish, or be seen waiting again, before anything else
 * runs.
 */
final class Follower {

    private static final long POLL = TimeUnit.MILLISECONDS.toNanos(10);

    private final int count;
    private final Turnstile turnstile;
    private final LockWatch watch;
    private final Trace trace;
    private final Result[] results;
    private final Duration timeout;
    private final long timeoutNanos;
    // Transactions whose released step is waiting on a lock, and what each was last seen waiting behind.
    private final Set<Integer> stuck = new TreeSet<>();
    private final Map<Integer, Integer> behind = new HashMap<>();
    // What each stuck step is waiting for, where the database says: a new wait can be behind the
    // same transaction as before, on a different lock.
    private final Map<Integer, String> awaiting = new HashMap<>();

    Follower(int count, Turnstile turnstile, LockWatch watch, Trace trace, Result[] results, Duration timeout,
            long timeoutNanos) {
        this.count = count;
        this.turnstile = turnstile;
        this.watch = watch;
        this.trace = trace;
        this.results = results;
        this.timeout = timeout;
        this.timeoutNanos = timeoutNanos;
    }

    void follow(Schedule schedule) throws Stop, InterruptedException, SQLException {
        for (int i = 0; i < schedule.size(); i++) {
            int n = schedule.order().get(i);
            String at = "position " + (i + 1) + " of \"" + schedule + "\"";
            if (n > count) {
                throw new Stop(at + " names transaction " + n + ", but there are only " + count);
            }
            awaitTurn(n, at);
            turnstile.release(n);
            finish(n, at);
            settle(at);
        }
        for (int n = 1; n <= count; n++) {
            while (stuck.contains(n)) {
                if (!deadlocked()) {
                    throw new Stop("the schedule ran out while transaction " + n
                            + " was still waiting on a lock held by " + holder(n));
                }
                awaitDatabase();
            }
            Step next = await(n, "the end of the schedule");
            if (next != null) {
                throw new Stop("the schedule ran out while transaction " + n + " still had a step to take: "
                        + (next.sql() == null ? next.kind() : next.sql()));
            }
        }
    }

    /**
     * Runs until every transaction has finished, asking {@code chooser} at each step which of the
     * transactions parked at the gate goes next. Returns the schedule that was followed.
     */
    List<Integer> choose(Chooser chooser) throws Stop, InterruptedException, SQLException {
        List<Integer> taken = new ArrayList<>();
        while (true) {
            Set<Integer> ready = ready();
            if (ready.isEmpty()) {
                if (stuck.isEmpty()) {
                    return taken;
                }
                // Everyone still running is waiting on someone else.
                awaitDatabase();
                continue;
            }
            // Sorted, because Set.copyOf iterates in an order that changes from one JVM to the next,
            // and the explorer branches in the order it is handed.
            int n = chooser.next(List.copyOf(taken), Collections.unmodifiableSortedSet(new TreeSet<>(ready)));
            if (!ready.contains(n)) {
                throw new IllegalStateException("chose transaction " + n + ", which is not one of " + ready);
            }
            taken.add(n);
            String at = "position " + taken.size() + " of \"" + new Schedule(taken) + "\"";
            turnstile.release(n);
            finish(n, at);
            settle(at);
        }
    }

    /**
     * The transactions parked at the gate. Every transaction that is not stuck is waited for until
     * it parks or finishes first, or one still between steps could be missed.
     */
    private Set<Integer> ready() throws Stop, InterruptedException {
        Set<Integer> ready = new TreeSet<>();
        for (int n = 1; n <= count; n++) {
            if (!stuck.contains(n) && await(n, "a choice of step") != null) {
                ready.add(n);
            }
        }
        return ready;
    }

    /** Waits until transaction n is parked at the gate, ready for its next step. */
    private void awaitTurn(int n, String at) throws Stop, InterruptedException, SQLException {
        if (stuck.contains(n)) {
            // Its wait may have ended since the last step settled.
            settle(at);
        }
        while (stuck.contains(n)) {
            if (!deadlocked()) {
                throw new Stop(at + " asks for transaction " + n + ", whose step is still waiting on a lock held by "
                        + holder(n));
            }
            awaitDatabase();
        }
        if (await(n, at) == null) {
            // A worker stores its result before it reports finishing, and both go through the
            // turnstile's lock, so the result is visible here.
            throw new Stop(at + " asks for transaction " + n + ", which has already finished: "
                    + ended(results[n - 1]));
        }
    }

    /** Waits for transaction n's released step to finish, or to be seen waiting on a lock. */
    private void finish(int n, String at) throws Stop, InterruptedException, SQLException {
        long deadline = deadline();
        while (!turnstile.awaitDone(n, POLL)) {
            if (waitsOnALock(n)) {
                // A deadlock this wait closes ends in a failure inside this step, before anything
                // else runs, rather than whenever the database next looks.
                if (turnstile.awaitDone(n, watch.settleNanos())) {
                    return;
                }
                if (waitsOnALock(n)) {
                    stuck.add(n);
                    trace.markWaiting(n);
                    return;
                }
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new Stop("the step transaction " + n + " took at " + at + " did not finish within " + timeout
                        + (watch.sees()
                                ? ", and the database does not say it is waiting on a lock"
                                : ". It may be waiting on a lock another transaction holds"));
            }
        }
    }

    /**
     * Lets every stuck step settle: finish, or be seen waiting on a lock again. A finished step
     * can free others, for instance when an error aborts its transaction, so this repeats until
     * nothing changes.
     *
     * <p>When one step frees two waiting steps at once, they race, and the database decides which
     * goes first. The loser ends up waiting behind the winner, which is how that shows: a step
     * still stuck, now behind a step freed in the same settle, and behind someone else before.
     * This only sees the race when the step that was not in front wins. When the one in front
     * wins, the run looks the same as one where the order was fixed, although the other ending was
     * possible. PostgreSQL usually keeps updates of a row in line, but each re-reads the newest
     * version of the row when the holder commits, and the one behind can get there first.
     */
    private void settle(String at) throws Stop, InterruptedException, SQLException {
        Map<Integer, Integer> before = new HashMap<>(behind);
        Map<Integer, Integer> seen = new HashMap<>(behind);
        Map<Integer, String> seenAwaiting = new HashMap<>(awaiting);
        Set<Integer> freed = new TreeSet<>();
        Set<Integer> waitedAnew = new TreeSet<>();
        while (true) {
            settleStuck(freed);
            // A step freed here may have gone on to wait behind someone else. Like a step that has
            // just been released, it gets the time the database needs to decide whether that wait
            // closes a deadlock, or the deadlock breaks at some moment later on and what is ready
            // next depends on timing.
            Set<Integer> fresh = new TreeSet<>();
            for (int n : stuck) {
                if (!Objects.equals(behind.get(n), seen.get(n))
                        || !Objects.equals(awaiting.get(n), seenAwaiting.get(n))) {
                    fresh.add(n);
                    seen.put(n, behind.get(n));
                    seenAwaiting.put(n, awaiting.get(n));
                }
            }
            if (fresh.isEmpty()) {
                break;
            }
            waitedAnew.addAll(fresh);
            if (!awaitAnyDone(fresh, watch.settleNanos())) {
                break;
            }
        }
        // PostgreSQL fails whichever wait's deadlock check runs first. Two waits that started in
        // the same step reach their checks at nearly the same moment, so either could be failed.
        if (watch.racesToFindDeadlocks() && waitedAnew.size() > 1) {
            for (int n : waitedAnew) {
                if (freed.contains(n) && turnstile.outcome(n) instanceof Outcome.Failed failed
                        && "40P01".equals(failed.sqlState())) {
                    throw new Stop("the step at " + at + " set off waits that deadlocked each other, and PostgreSQL"
                            + " chose to fail transaction " + n + " by whichever check ran first, so this schedule"
                            + " cannot be replayed exactly", true);
                }
            }
        }
        for (int n : stuck) {
            Integer now = behind.get(n);
            if (now != null && freed.contains(now) && !now.equals(before.get(n))) {
                throw new Stop("the step at " + at + " let transactions " + now + " and " + n + " stop waiting at once,"
                        + " and the database chose which went first, so this schedule cannot be replayed exactly",
                        true);
            }
        }
    }

    /** One pass over the stuck steps, repeated until none finishes. */
    private void settleStuck(Set<Integer> freed) throws Stop, InterruptedException, SQLException {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int n : new ArrayList<>(stuck)) {
                long deadline = deadline();
                while (!turnstile.done(n) && !waitsOnALock(n)) {
                    if (System.nanoTime() - deadline >= 0) {
                        throw new Stop("transaction " + n + "'s step stopped waiting on its lock but did not finish"
                                + " within " + timeout);
                    }
                    turnstile.awaitDone(n, POLL);
                }
                if (turnstile.done(n)) {
                    stuck.remove(n);
                    freed.add(n);
                    changed = true;
                }
            }
        }
    }

    /** Waits up to {@code nanos} for any of {@code steps} to finish, and says whether one did. */
    private boolean awaitAnyDone(Set<Integer> steps, long nanos) throws InterruptedException {
        long deadline = System.nanoTime() + nanos;
        while (steps.stream().noneMatch(turnstile::done)) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            turnstile.awaitAnything(Math.min(left, POLL));
        }
        return true;
    }

    /**
     * Whether n waits on a lock that is really held, and if so, records behind whom. H2 can still
     * name a holder for a moment after it has committed, so a transaction counts as a holder only
     * while it has a transaction open or a step still running. A running step can already hold
     * locks, such as rows its statement reached first.
     */
    private boolean waitsOnALock(int n) throws SQLException {
        for (int blocker : watch.blockers(n)) {
            if (blocker == LockWatch.OUTSIDE || turnstile.holding(blocker) || !turnstile.done(blocker)) {
                behind.put(n, blocker);
                awaiting.put(n, watch.awaited(n));
                return true;
            }
        }
        return false;
    }

    /** True when every transaction still running is stuck, so only the database can break the cycle. */
    private boolean deadlocked() {
        for (int n = 1; n <= count; n++) {
            if (!turnstile.finished(n) && !stuck.contains(n)) {
                return false;
            }
        }
        return true;
    }

    /** Waits for the database to break a deadlock by failing one of the stuck steps. */
    private void awaitDatabase() throws Stop, InterruptedException, SQLException {
        long deadline = deadline();
        while (stuck.stream().noneMatch(turnstile::done)) {
            if (System.nanoTime() - deadline >= 0) {
                throw new Stop("every transaction still running is waiting on a lock another one holds, and the"
                        + " database did not break the deadlock within " + timeout);
            }
            turnstile.awaitAnything(POLL);
        }
        settle("the end of a deadlock");
    }

    private String holder(int n) {
        int blocker = behind.getOrDefault(n, LockWatch.NONE);
        return blocker == LockWatch.OUTSIDE ? "a session outside the run" : "transaction " + blocker;
    }

    private Step await(int n, String at) throws Stop, InterruptedException {
        try {
            return turnstile.await(n, deadline());
        } catch (TimeoutException e) {
            throw new Stop("transaction " + n + " neither took its next step nor finished within " + timeout
                    + " (at " + at + ")");
        }
    }

    private static String ended(Result result) {
        return switch (result) {
            case Result.Committed committed -> "it committed";
            case Result.RolledBack rolledBack -> "it rolled back after " + rolledBack.cause();
        };
    }

    private long deadline() {
        // Overflows for a very long timeout, which is fine: deadlines are only ever compared by
        // subtracting nanoTime, and that wraps back round.
        return System.nanoTime() + timeoutNanos;
    }
}

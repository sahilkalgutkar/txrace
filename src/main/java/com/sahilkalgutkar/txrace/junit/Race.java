package com.sahilkalgutkar.txrace.junit;

import com.sahilkalgutkar.txrace.check.Checker;
import com.sahilkalgutkar.txrace.check.Invariant;
import com.sahilkalgutkar.txrace.check.Verdict;
import com.sahilkalgutkar.txrace.schedule.Explorer;
import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * The race a {@link RaceTest} runs: where it runs, what every run starts from, what to look at
 * afterwards, and the transactions. {@link #run} explores and checks them, and the extension
 * judges the verdict once the test method returns, so the test has nothing to assert unless it
 * wants to look closer.
 *
 * <p>One race per test. The bound on preemptions comes from the annotation.
 */
public final class Race {

    private final int preemptions;
    private DataSource database;
    private Explorer.Setup setup;
    private Explorer.Observation observation = database -> null;
    private boolean returnValues = true;
    private Invariant invariant;
    private Duration timeout;
    private Verdict verdict;

    Race(int preemptions) {
        this.preemptions = preemptions;
    }

    /** The database the transactions run against. Every run gets connections of its own from it. */
    public Race on(DataSource database) {
        this.database = Objects.requireNonNull(database, "database");
        return this;
    }

    /**
     * Puts the database into the state every run starts from. It runs before each order, so it
     * has to put back whatever the last run changed, not just add to it.
     */
    public Race startingFrom(Explorer.Setup setup) {
        this.setup = Objects.requireNonNull(setup, "setup");
        return this;
    }

    /** What to read after each run, to be judged along with what the transactions returned. */
    public Race observing(Explorer.Observation observation) {
        this.observation = Objects.requireNonNull(observation, "observation");
        return this;
    }

    /** Judges only which transactions committed and what was observed. See {@link Checker#ignoringReturnValues()}. */
    public Race ignoringReturnValues() {
        this.returnValues = false;
        return this;
    }

    /** Judges each ending by {@code invariant} instead of by the serial orders. See {@link Checker#judgingBy}. */
    public Race judgingBy(Invariant invariant) {
        this.invariant = Objects.requireNonNull(invariant, "invariant");
        return this;
    }

    /** The scheduler's timeout for each run. See {@link Explorer#withTimeout}. */
    public Race withTimeout(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        return this;
    }

    /**
     * Runs the transactions in every order within the bound and checks each ending. The verdict
     * is returned for a test that wants to look closer; the extension judges it either way.
     */
    public Verdict run(Transaction... transactions) throws SQLException {
        if (verdict != null) {
            throw new IllegalStateException("this race has already run: a race test runs one race");
        }
        if (database == null) {
            throw new IllegalStateException("say which database the race runs on, with on(dataSource)");
        }
        if (setup == null) {
            throw new IllegalStateException("say what every run starts from, with startingFrom(setup)."
                    + " Without it each run starts where the last one ended, and no two orders can be compared");
        }
        if (transactions.length < 2) {
            throw new IllegalArgumentException("a race needs at least two transactions, got " + transactions.length);
        }
        Explorer explorer = new Explorer(database, setup).observing(observation).withPreemptions(preemptions);
        if (timeout != null) {
            explorer = explorer.withTimeout(timeout);
        }
        Checker checker = new Checker(explorer);
        if (!returnValues) {
            checker = checker.ignoringReturnValues();
        }
        if (invariant != null) {
            checker = checker.judgingBy(invariant);
        }
        verdict = checker.check(List.of(transactions));
        return verdict;
    }

    /** The verdict, or null if the race never ran. */
    Verdict verdict() {
        return verdict;
    }
}

package com.sahilkalgutkar.txrace.zoo;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.check.Checker;
import com.sahilkalgutkar.txrace.check.Ending;
import com.sahilkalgutkar.txrace.check.Verdict;
import com.sahilkalgutkar.txrace.jdbc.PostgresExtension;
import com.sahilkalgutkar.txrace.schedule.Exploration;
import com.sahilkalgutkar.txrace.schedule.Explorer;
import com.sahilkalgutkar.txrace.schedule.Result;
import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * How a plain stress test does against exploration on each bug in the zoo. Not part of the build,
 * since it takes a couple of minutes and its numbers depend on the machine: run it with
 * {@code ./mvnw test -Dtest=ZooBenchmark}, and it prints a table.
 *
 * <p>The stress test is the one people write: each transaction on a thread of its own and a
 * connection of its own, started together, run many times from the same starting state. Each
 * trial is judged the same way the checker judges an order, against the endings of the serial
 * orders, so a trial that shows the bug is one no serial order could have produced. It runs three
 * times: with the threads released at the same instant, which is the best a stress test can do,
 * then with each one starting up to 1 ms and up to 10 ms late, which is closer to how two requests
 * arrive. The last row is a fix that looks right and is not. Every real fix goes through the
 * stress test too, and must never be flagged, or the harness itself is wrong.
 */
@ExtendWith(PostgresExtension.class)
class ZooBenchmark {

    private static final List<Specimen> ZOO = List.of(new Overdraft(), new DuplicateSignup(), new LastOwner(),
            new RefreshTokenReuse(), new CodeReuse(), new FamilyRevocation());
    private static final int TRIALS = 1000;
    /** How far apart the stress test may start its threads: together, up to 1 ms, and up to 10 ms. */
    private static final long[] SPREADS = {0, TimeUnit.MILLISECONDS.toNanos(1), TimeUnit.MILLISECONDS.toNanos(10)};

    /** A row of the table: a twin to look for a bug in, under a name. */
    private record Row(String name, Specimen specimen, Specimen.Twin twin) {}

    /** How one way of looking for a bug did: how often it saw it, and how soon. */
    private record Detection(int hits, int tries, int firstAt, long totalNanos) {

        String describe() {
            return hits + "/" + tries + (hits == 0 ? ", never" : ", first at #" + firstAt);
        }
    }

    @Test
    void stressTestingAgainstExploration(DataSource postgres) throws Exception {
        try (Connection connection = postgres.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DO $$ BEGIN EXECUTE format('ALTER DATABASE %I SET deadlock_timeout = %L', "
                    + "current_database(), '100ms'); END $$");
            for (Specimen specimen : ZOO) {
                specimen.create(statement);
            }
        }
        List<Row> rows = new ArrayList<>();
        ZOO.forEach(specimen -> rows.add(new Row(specimen.getClass().getSimpleName(), specimen, specimen.buggy())));
        // A fix that looks right and is not: one pass of the bulk revocation.
        rows.add(new Row("FamilyRevocation, one pass", new FamilyRevocation(), new Specimen.Twin(
                FamilyRevocation::rotatedOnce,
                List.of(RefreshTokenReuse.consumeIfUnused(1), FamilyRevocation.revokeOnce()))));
        StringBuilder table = new StringBuilder()
                .append("| Bug | Exploration, up to two preemptions | Stress, together | up to 1 ms apart | up to 10 ms apart |\n")
                .append("|---|---|---|---|---|\n");
        StringBuilder times = new StringBuilder();
        ExecutorService threads = Executors.newCachedThreadPool();
        try {
            for (Row row : rows) {
                long start = System.nanoTime();
                Verdict verdict = new Checker(new Explorer(postgres, row.twin().reset())
                        .observing(row.specimen()::observe).withPreemptions(2)).check(row.twin().transactions());
                Detection explored = explored(verdict, System.nanoTime() - start);
                Set<Ending> serial = verdict.serial().stream().map(Verdict.Serial::ending).collect(Collectors.toSet());
                table.append("| `").append(row.name()).append("` | ").append(explored.hits()).append(" of ")
                        .append(explored.tries()).append(" orders fail, first at #").append(explored.firstAt())
                        .append(" (").append(millis(explored.totalNanos())).append(")");
                times.append(row.name()).append(": check ").append(millis(explored.totalNanos()));
                for (long spread : SPREADS) {
                    Detection stressed = stress(postgres, row.specimen(), row.twin(), serial, spread, threads);
                    table.append(" | ").append(stressed.describe());
                    times.append(", stress ").append(millis(stressed.totalNanos()));
                }
                table.append(" |\n");
                times.append('\n');
            }
            ZOO.forEach(specimen -> assertFixedTwinNeverFlagged(postgres, specimen, threads));
        } finally {
            threads.shutdownNow();
        }
        System.out.println("=====TIMES\n" + times + "=====END");
        System.out.println("=====BENCHMARK\n" + table + "=====END");
    }

    /** Where the first failing order came in the order the explorer ran them, and how long the whole check took. */
    private static Detection explored(Verdict verdict, long totalNanos) {
        List<Exploration.Explored> runs = verdict.explored().runs();
        int first = 0;
        for (int i = 0; i < runs.size(); i++) {
            int index = i;
            if (verdict.violations().stream().anyMatch(violation -> violation == runs.get(index))) {
                first = i + 1;
                break;
            }
        }
        return new Detection(verdict.violations().size(), runs.size(), first, totalNanos);
    }

    private static Detection stress(DataSource database, Specimen specimen, Specimen.Twin twin, Set<Ending> serial,
            long spreadNanos, ExecutorService threads) throws Exception {
        int hits = 0;
        int firstAt = 0;
        long start = System.nanoTime();
        for (int trial = 1; trial <= TRIALS; trial++) {
            if (!serial.contains(once(database, specimen, twin, spreadNanos, threads))) {
                hits++;
                if (firstAt == 0) {
                    firstAt = trial;
                }
            }
        }
        return new Detection(hits, TRIALS, firstAt, System.nanoTime() - start);
    }

    private static void assertFixedTwinNeverFlagged(DataSource database, Specimen specimen, ExecutorService threads) {
        try {
            Specimen.Twin fixed = specimen.fixed();
            Verdict verdict = new Checker(new Explorer(database, fixed.reset()).observing(specimen::observe)
                    .withPreemptions(2)).check(fixed.transactions());
            Set<Ending> serial = verdict.serial().stream().map(Verdict.Serial::ending).collect(Collectors.toSet());
            for (long spread : SPREADS) {
                assertThat(stress(database, specimen, fixed, serial, spread, threads).hits())
                        .as("%s's fix flagged under stress", specimen.getClass().getSimpleName()).isZero();
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** One trial: every transaction at once, each on its own thread and connection, then what it left. */
    private static Ending once(DataSource database, Specimen specimen, Specimen.Twin twin, long spreadNanos,
            ExecutorService threads) throws Exception {
        twin.reset().prepare(database);
        List<Transaction> transactions = twin.transactions();
        CyclicBarrier start = new CyclicBarrier(transactions.size());
        List<Future<Result>> running = new ArrayList<>();
        for (Transaction transaction : transactions) {
            long delay = spreadNanos == 0 ? 0 : ThreadLocalRandom.current().nextLong(spreadNanos);
            running.add(threads.submit(() -> alone(database, transaction, start, delay)));
        }
        List<Result> results = new ArrayList<>();
        for (Future<Result> result : running) {
            results.add(result.get(30, TimeUnit.SECONDS));
        }
        return ending(results, specimen.observe(database));
    }

    /** Runs a transaction the way the scheduler does, minus the scheduler: autocommit off, commit, roll back on a throw. */
    private static Result alone(DataSource database, Transaction transaction, CyclicBarrier start, long delayNanos)
            throws Exception {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            start.await();
            if (delayNanos > 0) {
                LockSupport.parkNanos(delayNanos);
            }
            try {
                Object value = transaction.run(connection);
                connection.commit();
                return new Result.Committed(value);
            } catch (Exception e) {
                connection.rollback();
                return new Result.RolledBack(e);
            }
        }
    }

    /** The checker's rule: a transaction the database refused is left out, and one that gave up is part of the ending. */
    private static Ending ending(List<Result> results, Object state) {
        SortedMap<Integer, Object> committed = new TreeMap<>();
        SortedMap<Integer, String> aborted = new TreeMap<>();
        for (int i = 0; i < results.size(); i++) {
            switch (results.get(i)) {
                case Result.Committed done -> committed.put(i + 1, done.value());
                case Result.RolledBack back when !(back.cause() instanceof SQLException) ->
                        aborted.put(i + 1, back.cause().getClass().getName());
                case Result.RolledBack refused -> {
                }
            }
        }
        return new Ending(committed, aborted, state);
    }

    private static String millis(long nanos) {
        double ms = nanos / 1e6;
        return ms < 1000 ? String.format("%.0f ms", ms) : String.format("%.1f s", ms / 1000);
    }
}

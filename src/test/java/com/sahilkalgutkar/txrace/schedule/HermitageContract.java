package com.sahilkalgutkar.txrace.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.check.Checker;
import com.sahilkalgutkar.txrace.check.Verdict;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs every Hermitage scenario at every isolation level of a database, through the scheduler, in
 * Hermitage's own interleavings, and checks which anomalies each level prevents.
 */
abstract class HermitageContract {

    record Level(String name, int jdbc) {
    }

    DataSource database;

    @BeforeEach
    void createTable(DataSource database) throws SQLException {
        this.database = database;
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            prepare(statement);
        }
        Hermitage.createTable(database);
    }

    /** Anything the database needs first, such as lock and deadlock timeouts suited to a test. */
    abstract void prepare(Statement statement) throws SQLException;

    abstract List<Level> levels();

    /** For each level, the anomalies it is expected to prevent. */
    abstract Map<String, List<String>> prevented();

    /**
     * Follows an interleaving the way Hermitage's hand-run sessions do: the next step is the
     * earliest one listed whose transaction can take it. A transaction stuck on a lock waits for
     * its turn to come round again, and the steps of one that has already finished, because the
     * database refused it, are passed over.
     */
    static Chooser following(Schedule order) {
        List<Integer> slots = new ArrayList<>(order.order());
        return (taken, ready) -> {
            for (int i = 0; i < slots.size(); i++) {
                if (ready.contains(slots.get(i))) {
                    return slots.remove(i);
                }
            }
            // Steps Hermitage does not list, such as the rollback after a refusal.
            return Collections.min(ready);
        };
    }

    static Transaction at(int level, Transaction transaction) {
        return connection -> {
            connection.setTransactionIsolation(level);
            return transaction.run(connection);
        };
    }

    /** For each level, the anomalies that did not show when the scenario ran. */
    Map<String, List<String>> measure() throws SQLException {
        Scheduler scheduler = new Scheduler(database);
        Map<String, List<String>> prevented = new LinkedHashMap<>();
        for (Level level : levels()) {
            List<String> held = new ArrayList<>();
            for (Hermitage.Scenario scenario : Hermitage.ALL) {
                Hermitage.setUp(database);
                Run run = scheduler.run(following(Schedule.parse(scenario.order())),
                        scenario.transactions().stream().map(t -> at(level.jdbc(), t)).toList());
                if (!scenario.detector().occurred(run, Hermitage.rows(database))) {
                    held.add(scenario.anomaly());
                }
            }
            prevented.put(level.name(), held);
        }
        return prevented;
    }

    /** The results as a table, in Hermitage's notation: ✓ prevented, — can occur. */
    static String table(Map<String, List<String>> prevented) {
        StringBuilder out = new StringBuilder("| Level |");
        Hermitage.ALL.forEach(scenario -> out.append(' ').append(scenario.anomaly()).append(" |"));
        out.append("\n|---|");
        Hermitage.ALL.forEach(scenario -> out.append(":-:|"));
        prevented.forEach((level, held) -> {
            out.append("\n| ").append(level).append(" |");
            Hermitage.ALL.forEach(scenario -> out.append(held.contains(scenario.anomaly()) ? " ✓ |" : " — |"));
        });
        return out.append('\n').toString();
    }

    /** The checker's verdict on a scenario at a level, watching every row. */
    Verdict check(Level level, Hermitage.Scenario scenario) throws SQLException {
        return new Checker(new Explorer(database, Hermitage::setUp)
                .observing(Hermitage::rows).withPreemptions(2))
                .check(scenario.transactions().stream().map(t -> at(level.jdbc(), t)).toList());
    }

    /** Whether one of the orders the checker flagged shows the scenario's anomaly. */
    @SuppressWarnings("unchecked")
    static boolean shows(Hermitage.Scenario scenario, Verdict verdict) {
        return verdict.violations().stream()
                .anyMatch(order -> scenario.detector().occurred(order.run(), (List<List<Integer>>) order.state()));
    }

    /**
     * For every anomaly a level allows, the checker has to find an order no serial order explains,
     * on its own, without being given Hermitage's interleaving, and the anomaly has to show in that
     * order. Two preemptions are enough for all of these, and keep the search short.
     */
    @Test
    void theCheckerFindsEveryAnomalyALevelAllowsWithoutBeingShownWhere() throws SQLException {
        Map<String, List<String>> prevented = prevented();
        List<String> missed = new ArrayList<>();
        for (Level level : levels()) {
            for (Hermitage.Scenario scenario : Hermitage.ALL) {
                if (prevented.get(level.name()).contains(scenario.anomaly())) {
                    continue;
                }
                if (!shows(scenario, check(level, scenario))) {
                    missed.add(scenario.anomaly() + " at " + level.name());
                }
            }
        }

        assertThat(missed).isEmpty();
    }

    /** And at a level that prevents every anomaly, the checker must find nothing at all. */
    @Test
    void theCheckerPassesEveryScenarioAtALevelThatPreventsThemAll() throws SQLException {
        List<String> flagged = new ArrayList<>();
        for (Level level : levels()) {
            if (prevented().get(level.name()).size() < Hermitage.ALL.size()) {
                continue;
            }
            for (Hermitage.Scenario scenario : Hermitage.ALL) {
                if (!check(level, scenario).violations().isEmpty()) {
                    flagged.add(scenario.anomaly() + " at " + level.name());
                }
            }
        }

        assertThat(flagged).isEmpty();
    }

    @Test
    void eachLevelPreventsWhatItShould() throws SQLException {
        Map<String, List<String>> measured = measure();
        System.out.println(table(measured));

        assertThat(measured).isEqualTo(prevented());
    }
}

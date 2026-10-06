package com.sahilkalgutkar.txrace.check;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahilkalgutkar.txrace.schedule.Exploration;
import com.sahilkalgutkar.txrace.schedule.Result;
import com.sahilkalgutkar.txrace.schedule.Run;
import com.sahilkalgutkar.txrace.schedule.Schedule;
import com.sahilkalgutkar.txrace.trace.Outcome;
import com.sahilkalgutkar.txrace.trace.Step;
import com.sahilkalgutkar.txrace.trace.Trace;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class VerdictTest {

    private static Ending ending(Map<Integer, Object> committed, Object state) {
        return new Ending(new TreeMap<>(committed), state);
    }

    /** Three transactions; 1 and 2 committed, 3 was refused, and the balance ended at 150. */
    private static Exploration.Explored lostUpdateWithARefusal() {
        Trace trace = new Trace();
        for (int n = 1; n <= 3; n++) {
            trace.end(trace.begin(Step.statement(n, 1, "UPDATE account SET balance = ?", List.of(n))),
                    new Outcome.Updated(1));
        }
        Run run = new Run(Schedule.parse("1 2 3"), trace, List.of(new Result.Committed(130),
                new Result.Committed(150), new Result.RolledBack(new SQLException("refused", "40001"))));
        return new Exploration.Explored(run, 150, 1);
    }

    private static final List<Verdict.Serial> SERIAL = List.of(
            new Verdict.Serial(List.of(1, 2), ending(Map.of(1, 130, 2, 180), 180)),
            new Verdict.Serial(List.of(2, 1), ending(Map.of(1, 180, 2, 150), 180)),
            new Verdict.Serial(List.of(1, 2, 3), ending(Map.of(1, 130, 2, 180, 3, 250), 250)));

    @Test
    void listsTheSerialRunsTheOrderWasComparedWith() {
        Exploration.Explored order = lostUpdateWithARefusal();
        Verdict verdict = new Verdict(3, SERIAL, new Exploration(List.of(order), List.of(), true), List.of(order),
                true);

        assertThat(verdict.report())
                .contains("It ended with transaction 1 returned 130, transaction 2 returned 150; observed 150.\n"
                        + "The database refused transaction 3, so it is compared with the rest run one after another:\n"
                        + "  1 then 2: transaction 1 returned 130, transaction 2 returned 180; observed 180\n"
                        + "  2 then 1: transaction 1 returned 180, transaction 2 returned 150; observed 180\n")
                .doesNotContain("1 then 2 then 3");
    }

    @Test
    void saysWhoCommittedWhenReturnValuesWereIgnored() {
        Exploration.Explored order = lostUpdateWithARefusal();
        Verdict verdict = new Verdict(3, SERIAL, new Exploration(List.of(order), List.of(), true), List.of(order),
                false);

        assertThat(verdict.report())
                .contains("It ended with transactions 1, 2 committed; observed 150.")
                .contains("  1 then 2: transactions 1, 2 committed; observed 180")
                .doesNotContain("returned");
    }

    @Test
    void doesNotHoldWhenSomeOrdersWereNeverJudged() {
        Exploration.Explored fine = new Exploration.Explored(
                new Run(Schedule.parse(""), new Trace(), List.of()), 100, 0);
        Exploration.Refused refused = new Exploration.Refused(Schedule.parse("1 2"), "cannot be replayed exactly");

        Verdict withARefusal = new Verdict(0, SERIAL, new Exploration(List.of(fine), List.of(refused), true),
                List.of(), true);
        Verdict cutShort = new Verdict(0, SERIAL, new Exploration(List.of(fine), List.of(), false), List.of(), true);
        Verdict whole = new Verdict(0, SERIAL, new Exploration(List.of(fine), List.of(), true), List.of(), true);

        assertThat(withARefusal.holds()).isFalse();
        assertThat(cutShort.holds()).isFalse();
        assertThat(whole.holds()).isTrue();
        assertThat(withARefusal.report()).contains("1 refused as unrepeatable and not judged")
                .endsWith("Every order that ran ended the way some serial order does, but not every order ran,"
                        + " so that is all this shows.\n");
        assertThat(cutShort.report()).contains("the search stopped at its limit")
                .endsWith("but not every order ran, so that is all this shows.\n");
        assertThat(whole.report()).endsWith("Every order ended the way some serial order does.\n");
    }
}

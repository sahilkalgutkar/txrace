package com.sahilkalgutkar.txrace.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TraceTest {

    @Test
    void numbersEventsInTheOrderTheyWereSent() {
        Trace trace = new Trace();

        int first = trace.begin(Step.statement(1, 1, "SELECT 1", List.of()));
        int second = trace.begin(Step.statement(2, 1, "SELECT 2", List.of()));
        trace.end(second, new Outcome.Rows());

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(trace.events()).extracting(Trace.Event::running).containsExactly(true, false);
    }

    @Test
    void refusesToEndAStepTwice() {
        Trace trace = new Trace();
        int seq = trace.begin(Step.commit(1, 1));
        trace.end(seq, new Outcome.Done());

        assertThatThrownBy(() -> trace.end(seq, new Outcome.Done()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCopyDoesNotSeeLaterSteps() {
        Trace trace = new Trace();
        trace.begin(Step.statement(1, 1, "SELECT 1", List.of()));

        Trace copy = trace.copy();
        trace.begin(Step.commit(1, 1));

        assertThat(copy.events()).hasSize(1);
        assertThat(trace.events()).hasSize(2);
    }

    @Test
    void filtersByConnection() {
        Trace trace = new Trace();
        trace.begin(Step.statement(1, 1, "SELECT 1", List.of()));
        trace.begin(Step.statement(2, 1, "SELECT 2", List.of()));
        trace.begin(Step.commit(1, 1));

        assertThat(trace.of(1)).extracting(Trace.Event::seq).containsExactly(1, 3);
    }

    @Test
    void rendersOneLinePerStep() {
        Trace trace = new Trace();
        trace.end(trace.begin(Step.statement(1, 1, "SELECT v FROM t WHERE id = ?", List.of(7))), new Outcome.Rows());
        trace.end(trace.begin(Step.statement(2, 1, "UPDATE t SET name = ?, data = ?, note = ? WHERE id = ?",
                Arrays.asList("it's", new byte[] {0x0a, (byte) 0xff}, null, 7))), new Outcome.Updated(1));
        trace.end(trace.begin(new Step(2, 1, Step.Kind.BATCH, "INSERT INTO t VALUES (?)",
                List.of(List.of(1), List.of(2)))), new Outcome.Batch(List.of(1L, 1L)));
        trace.end(trace.begin(Step.commit(1, 1)), new Outcome.Done());
        trace.end(trace.begin(Step.statement(1, 2, "INSERT INTO t VALUES (1)", List.of())),
                Outcome.failed(new SQLException("duplicate", "23505")));
        trace.begin(Step.rollback(2, 1));
        trace.end(trace.begin(new Step(3, 1, Step.Kind.STATEMENT, "DELETE FROM t", List.of(), true)),
                new Outcome.Updated(0));

        assertThat(trace.render()).isEqualTo("""
                  1  c1 t1  SELECT v FROM t WHERE id = ?  [7]  -> rows
                  2  c2 t1  UPDATE t SET name = ?, data = ?, note = ? WHERE id = ?  ['it''s', X'0aff', NULL, 7]  -> updated 1
                  3  c2 t1  INSERT INTO t VALUES (?)  [[1], [2]]  -> batch [1, 1]
                  4  c1 t1  COMMIT  -> done
                  5  c1 t2  INSERT INTO t VALUES (1)  -> failed 23505
                  6  c2 t1  ROLLBACK  -> running
                  7  c3 t1  DELETE FROM t  -> updated 0
                """);
        assertThat(trace).hasToString(trace.render());
    }

    @Test
    void describesAFailureThatIsNotAnSqlExceptionByItsMessage() {
        Outcome outcome = Outcome.failed(new IllegalStateException("boom"));
        Trace trace = new Trace();
        trace.end(trace.begin(Step.commit(1, 1)), outcome);

        assertThat(outcome).isEqualTo(new Outcome.Failed(null, 0, "java.lang.IllegalStateException: boom"));
        assertThat(trace.render()).endsWith("-> failed java.lang.IllegalStateException: boom\n");
    }
}

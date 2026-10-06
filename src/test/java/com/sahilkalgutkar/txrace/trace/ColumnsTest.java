package com.sahilkalgutkar.txrace.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnsTest {

    @Test
    void putsEachTransactionsStepsInItsOwnColumn() {
        Trace trace = new Trace();
        trace.end(trace.begin(Step.statement(1, 1, "SELECT balance FROM account WHERE id = ?", List.of(1))),
                new Outcome.Rows());
        trace.end(trace.begin(Step.statement(2, 1, "SELECT 1", List.of())), new Outcome.Rows());
        trace.end(trace.begin(Step.commit(1, 1)), new Outcome.Done());

        assertThat(Columns.render(trace, 2, 24)).isEqualTo("""
                     transaction 1             transaction 2
                  1  SELECT balance FROM
                     account WHERE id = ?
                     [1]  -> rows
                  2                            SELECT 1  -> rows
                  3  COMMIT  -> done
                """);
    }

    @Test
    void keepsSqlWrittenOverSeveralLinesInsideItsColumn() {
        Trace trace = new Trace();
        trace.end(trace.begin(Step.statement(2, 1, """
                UPDATE account
                	SET balance = balance + 1
                 WHERE id = 1""", List.of())), new Outcome.Updated(1));

        assertThat(Columns.render(trace, 2, 20)).isEqualTo("""
                     transaction 1         transaction 2
                  1                        UPDATE account SET
                                           balance = balance +
                                           1 WHERE id = 1  ->
                                           updated 1
                """);
        assertThatThrownBy(() -> Columns.render(trace, 2, 0)).hasMessageContaining("at least one character");
    }

    @Test
    void breaksAWordOnlyWhenItIsWiderThanTheColumn() {
        assertThat(Columns.wrap("abcdefghij kl", 4)).containsExactly("abcd", "efgh", "ij", "kl");
        assertThat(Columns.wrap("ab cd ef", 5)).containsExactly("ab cd", "ef");
    }
}

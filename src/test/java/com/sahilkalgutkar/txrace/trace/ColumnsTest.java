package com.sahilkalgutkar.txrace.trace;

import static org.assertj.core.api.Assertions.assertThat;

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
    void breaksAWordOnlyWhenItIsWiderThanTheColumn() {
        assertThat(Columns.wrap("abcdefghij kl", 4)).containsExactly("abcd", "efgh", "ij", "kl");
        assertThat(Columns.wrap("ab cd ef", 5)).containsExactly("ab cd", "ef");
    }
}

package com.sahilkalgutkar.txrace.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class StepTest {

    @Test
    void keepsNullParameters() {
        Step step = Step.statement(1, 1, "UPDATE t SET v = ? WHERE id = ?", Arrays.asList(null, 7));

        assertThat(step.parameters()).containsExactly(null, 7);
    }

    @Test
    void copiesParametersSoLaterChangesDoNotLeakIn() {
        List<Object> parameters = new ArrayList<>(List.of(1));
        Step step = Step.statement(1, 1, "SELECT ?", parameters);
        parameters.add(2);

        assertThat(step.parameters()).containsExactly(1);
        assertThatThrownBy(() -> step.parameters().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void commitAndRollbackEndTheTransaction() {
        assertThat(Step.commit(1, 3).endsTransaction()).isTrue();
        assertThat(Step.rollback(2, 1).endsTransaction()).isTrue();
        assertThat(Step.statement(1, 1, "SELECT 1", List.of()).endsTransaction()).isFalse();
        assertThat(Step.commit(1, 3).sql()).isNull();
    }

    @Test
    void statementsNeedSqlAndBoundariesRefuseIt() {
        assertThatThrownBy(() -> Step.statement(1, 1, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs SQL");
        assertThatThrownBy(() -> new Step(1, 1, Step.Kind.COMMIT, "COMMIT", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carries no SQL");
    }

    @Test
    void numbersStartAtOne() {
        assertThatThrownBy(() -> Step.commit(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Step.commit(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}

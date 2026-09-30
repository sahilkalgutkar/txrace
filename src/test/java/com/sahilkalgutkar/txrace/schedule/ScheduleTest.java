package com.sahilkalgutkar.txrace.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleTest {

    @Test
    void readsSpacesAndCommas() {
        assertThat(Schedule.parse("1 2 1").order()).containsExactly(1, 2, 1);
        assertThat(Schedule.parse(" 1,2 ,  12 ").order()).containsExactly(1, 2, 12);
        assertThat(Schedule.parse("").size()).isZero();
    }

    @Test
    void printsTheWayItIsWritten() {
        assertThat(Schedule.of(1, 2, 1, 1, 2, 2)).hasToString("1 2 1 1 2 2");
        assertThat(Schedule.parse("1,2")).isEqualTo(Schedule.of(1, 2));
    }

    @Test
    void refusesAnythingButTransactionNumbers() {
        assertThatThrownBy(() -> Schedule.parse("1 a")).hasMessageContaining("not a schedule");
        assertThatThrownBy(() -> Schedule.of(1, 0)).hasMessageContaining("numbered from 1");
    }

    @Test
    void cannotBeChangedAfterwards() {
        Schedule schedule = new Schedule(new ArrayList<>(List.of(1, 2)));

        assertThatThrownBy(() -> schedule.order().add(3)).isInstanceOf(UnsupportedOperationException.class);
    }
}

package com.sahilkalgutkar.txrace.schedule;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The order in which transactions take their steps, written as transaction numbers.
 *
 * <p>{@code "1 2 1 1 2 2"} means transaction 1 takes a step, then transaction 2, then 1 twice,
 * then 2 twice. Every step counts, including the commit at the end.
 */
public record Schedule(List<Integer> order) {

    public Schedule {
        order = List.copyOf(order);
        for (int transaction : order) {
            if (transaction < 1) {
                throw new IllegalArgumentException("transactions are numbered from 1, got " + transaction);
            }
        }
    }

    public static Schedule of(int... order) {
        return new Schedule(Arrays.stream(order).boxed().toList());
    }

    /** Reads numbers separated by spaces or commas. */
    public static Schedule parse(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return new Schedule(List.of());
        }
        try {
            return new Schedule(Arrays.stream(trimmed.split("[\\s,]+")).map(Integer::valueOf).toList());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a schedule: \"" + text + "\"", e);
        }
    }

    public int size() {
        return order.size();
    }

    @Override
    public String toString() {
        return order.stream().map(String::valueOf).collect(Collectors.joining(" "));
    }
}

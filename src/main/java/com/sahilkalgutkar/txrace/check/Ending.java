package com.sahilkalgutkar.txrace.check;

import com.sahilkalgutkar.txrace.schedule.Exploration;
import com.sahilkalgutkar.txrace.schedule.Result;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a run is judged by: what each transaction that committed returned, which ones gave up by
 * throwing, and what was observed afterwards.
 *
 * <p>A transaction the database refused, one that died on an {@link SQLException} such as a
 * serialization failure or a deadlock, is left out altogether: the database undid it, so it left
 * nothing behind, and refusing is the database doing its job. A transaction that threw anything
 * else gave up by its own logic, and that is part of the ending, because giving up because of
 * what it read is exactly how an anomaly can show. Code that catches an SQLException and throws
 * something else is counted as giving up.
 */
public record Ending(SortedMap<Integer, Object> committed, SortedMap<Integer, String> aborted, Object state) {

    public Ending {
        committed = Collections.unmodifiableSortedMap(new TreeMap<>(committed));
        aborted = Collections.unmodifiableSortedMap(new TreeMap<>(aborted));
    }

    /** An ending where nothing gave up. */
    public Ending(SortedMap<Integer, Object> committed, Object state) {
        this(committed, new TreeMap<>(), state);
    }

    /**
     * The ending of a run of some of the transactions. {@code numbers} gives, for each transaction
     * in the run, its number among all of them.
     */
    static Ending of(Exploration.Explored explored, List<Integer> numbers) {
        SortedMap<Integer, Object> committed = new TreeMap<>();
        SortedMap<Integer, String> aborted = new TreeMap<>();
        List<Result> results = explored.run().results();
        for (int i = 0; i < results.size(); i++) {
            switch (results.get(i)) {
                case Result.Committed done -> committed.put(numbers.get(i), done.value());
                case Result.RolledBack back when !(back.cause() instanceof SQLException) ->
                        aborted.put(numbers.get(i), back.cause().getClass().getName());
                case Result.RolledBack refused -> {
                }
            }
        }
        return new Ending(committed, aborted, explored.state());
    }

    /** The transactions the database did not refuse: those that committed and those that gave up. */
    List<Integer> finished() {
        List<Integer> finished = new ArrayList<>(committed.keySet());
        finished.addAll(aborted.keySet());
        Collections.sort(finished);
        return finished;
    }

    /** The same ending with what each transaction returned forgotten, keeping which ones committed. */
    Ending withoutReturnValues() {
        SortedMap<Integer, Object> which = new TreeMap<>();
        committed.keySet().forEach(n -> which.put(n, null));
        return new Ending(which, aborted, state);
    }

    String describe(boolean returnValues) {
        Stream<String> committedParts = returnValues
                ? committed.entrySet().stream().map(entry -> "transaction " + entry.getKey() + " returned "
                        + entry.getValue())
                : committed.isEmpty() ? Stream.of() : Stream.of(committed.keySet().stream().map(String::valueOf)
                        .collect(Collectors.joining(", ", committed.size() == 1 ? "transaction " : "transactions ",
                                " committed")));
        Stream<String> abortedParts = aborted.entrySet().stream()
                .map(entry -> "transaction " + entry.getKey() + " gave up with " + entry.getValue());
        String parts = Stream.concat(committedParts, abortedParts).collect(Collectors.joining(", "));
        return (parts.isEmpty() ? "nothing committed" : parts) + "; observed " + state;
    }
}

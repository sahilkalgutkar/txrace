package com.sahilkalgutkar.txrace.check;

import com.sahilkalgutkar.txrace.schedule.Exploration;
import com.sahilkalgutkar.txrace.schedule.Result;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * What a run is judged by: what each transaction that committed returned, and what was observed
 * afterwards. A transaction that rolled back is left out, because it left nothing behind.
 */
public record Ending(SortedMap<Integer, Object> committed, Object state) {

    public Ending {
        committed = Collections.unmodifiableSortedMap(new TreeMap<>(committed));
    }

    /**
     * The ending of a run of some of the transactions. {@code numbers} gives, for each transaction
     * in the run, its number among all of them.
     */
    static Ending of(Exploration.Explored explored, List<Integer> numbers) {
        SortedMap<Integer, Object> committed = new TreeMap<>();
        List<Result> results = explored.run().results();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) instanceof Result.Committed done) {
                committed.put(numbers.get(i), done.value());
            }
        }
        return new Ending(committed, explored.state());
    }

    /** The same ending with what each transaction returned forgotten, keeping which ones committed. */
    Ending withoutReturnValues() {
        SortedMap<Integer, Object> which = new TreeMap<>();
        committed.keySet().forEach(n -> which.put(n, null));
        return new Ending(which, state);
    }

    String describe() {
        String returned = committed.isEmpty() ? "nothing committed" : committed.entrySet().stream()
                .map(entry -> "transaction " + entry.getKey() + " returned " + entry.getValue())
                .collect(Collectors.joining(", "));
        return returned + "; observed " + state;
    }
}

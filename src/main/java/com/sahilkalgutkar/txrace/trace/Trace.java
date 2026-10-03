package com.sahilkalgutkar.txrace.trace;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Every step, in the order the steps reached the driver.
 *
 * <p>A step is added when it is sent and gets its outcome when the driver answers, so a statement
 * that is stuck (waiting on a lock, say) shows up as still running rather than not at all.
 */
public final class Trace {

    /** One step and its outcome. The outcome is null until the driver answers. */
    public record Event(int seq, Step step, Outcome outcome) {
        public boolean running() {
            return outcome == null;
        }
    }

    private final List<Event> events = new ArrayList<>();

    /** Records a step as sent and returns its sequence number, starting at 1. */
    public synchronized int begin(Step step) {
        events.add(new Event(events.size() + 1, step, null));
        return events.size();
    }

    public synchronized void end(int seq, Outcome outcome) {
        Event event = events.get(seq - 1);
        if (!event.running()) {
            throw new IllegalStateException("step " + seq + " already ended");
        }
        events.set(seq - 1, new Event(seq, event.step(), outcome));
    }

    /** A copy that later steps will not change. */
    public synchronized Trace copy() {
        Trace copy = new Trace();
        copy.events.addAll(events);
        return copy;
    }

    public synchronized List<Event> events() {
        return List.copyOf(events);
    }

    /** The events of one connection, in order. */
    public List<Event> of(int connection) {
        return events().stream().filter(e -> e.step().connection() == connection).toList();
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        for (Event event : events()) {
            Step step = event.step();
            out.append(String.format("%3d  c%d t%d  ", event.seq(), step.connection(), step.transaction()));
            out.append(step.sql() == null ? step.kind().name() : step.sql());
            if (!step.parameters().isEmpty()) {
                out.append("  ").append(format(step.parameters()));
            }
            out.append("  -> ").append(describe(event.outcome())).append('\n');
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return render();
    }

    private static String describe(Outcome outcome) {
        return switch (outcome) {
            case null -> "running";
            case Outcome.Rows r -> "rows";
            case Outcome.Updated u -> "updated " + u.count();
            case Outcome.Batch b -> "batch " + b.counts();
            case Outcome.Done d -> "done";
            case Outcome.Failed f -> "failed " + (f.sqlState() == null ? f.message() : f.sqlState())
                    + (f.counts().isEmpty() ? "" : " after " + f.counts());
        };
    }

    private static String format(Object value) {
        return switch (value) {
            case null -> "NULL";
            case String s -> "'" + s.replace("'", "''") + "'";
            case byte[] bytes -> "X'" + HexFormat.of().formatHex(bytes) + "'";
            case List<?> list -> list.stream().map(Trace::format).collect(Collectors.joining(", ", "[", "]"));
            case Map.Entry<?, ?> column -> column.getKey() + "=" + format(column.getValue());
            default -> String.valueOf(value);
        };
    }
}

package com.sahilkalgutkar.txrace.trace;

import java.util.ArrayList;
import java.util.List;

/** Lays a trace out with one column per connection, so the order the steps interleaved is the shape of the page. */
public final class Columns {

    private Columns() {
    }

    /** {@code connections} columns, each {@code width} characters wide. */
    public static String render(Trace trace, int connections, int width) {
        if (width < 1) {
            throw new IllegalArgumentException("a column has to be at least one character wide, got " + width);
        }
        StringBuilder header = new StringBuilder("     ");
        for (int c = 1; c <= connections; c++) {
            header.append(pad("transaction " + c, width)).append("  ");
        }
        StringBuilder out = new StringBuilder(header.toString().stripTrailing()).append('\n');
        for (Trace.Event event : trace.events()) {
            int column = event.step().connection() - 1;
            List<String> lines = wrap(Trace.describe(event), width);
            for (int i = 0; i < lines.size(); i++) {
                out.append(i == 0 ? String.format("%3d  ", event.seq()) : "     ");
                out.append(" ".repeat((width + 2) * column)).append(lines.get(i).stripTrailing()).append('\n');
            }
        }
        return out.toString();
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    /**
     * Breaks at spaces where it can, and inside a word only when the word is wider than the column.
     * Newlines and tabs, from SQL written over several lines, count as spaces.
     */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String flat = text.strip().replaceAll("\\s*[\\r\\n]\\s*", " ").replace('\t', ' ');
        for (String word : flat.split(" ")) {
            while (word.length() > width) {
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}

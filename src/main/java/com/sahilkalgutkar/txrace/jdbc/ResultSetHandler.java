package com.sahilkalgutkar.txrace.jdbc;

import com.sahilkalgutkar.txrace.trace.Outcome;
import com.sahilkalgutkar.txrace.trace.Step;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Keeps a result set pointing back at the traced statement that produced it, and turns the row
 * changes an updatable result set makes into steps of their own.
 */
final class ResultSetHandler extends ProxyHandler {

    // Moves that take the cursor off the insert row.
    private static final Set<String> MOVES = Set.of("next", "previous", "first", "last", "absolute", "relative",
            "beforeFirst", "afterLast", "moveToCurrentRow");

    private final StatementHandler statement;
    // Column updates not written yet, as column=value pairs. H2 and pgjdbc both keep pending updates
    // when the cursor moves and write them at the next updateRow, so only a write or a cancel clears
    // them. The insert row keeps its own.
    private final List<Object> rowChanges = new ArrayList<>();
    private final List<Object> insertChanges = new ArrayList<>();
    private boolean onInsertRow;

    private ResultSetHandler(ResultSet real, StatementHandler statement) {
        super(real);
        this.statement = statement;
    }

    static ResultSet wrap(ResultSet real, StatementHandler statement) {
        return create(ResultSet.class, new ResultSetHandler(real, statement));
    }

    @Override
    Object handle(Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "getStatement" -> {
                return statement.proxy();
            }
            case "updateRow", "deleteRow" -> {
                return change(method, args, name.equals("deleteRow") ? List.of() : rowChanges, rowChanges);
            }
            case "insertRow" -> {
                return change(method, args, insertChanges, insertChanges);
            }
            case "refreshRow" -> {
                // A read of the current row from the database, so it waits its turn like any query.
                // The spec says pending updates are lost, and H2 does drop them.
                ConnectionHandler connection = statement.connection();
                Step step = connection.next(Step.Kind.STATEMENT, "ResultSet.refreshRow()", List.of());
                return connection.run(step, () -> {
                    Object result = pass(method, args);
                    rowChanges.clear();
                    return result;
                }, result -> new Outcome.Rows());
            }
            default -> {
                Object result = pass(method, args);
                if (name.equals("moveToInsertRow")) {
                    onInsertRow = true;
                } else if (MOVES.contains(name)) {
                    onInsertRow = false;
                } else if (name.equals("cancelRowUpdates")) {
                    rowChanges.clear();
                } else if (name.startsWith("update") && method.getDeclaringClass() == ResultSet.class) {
                    Object value = name.equals("updateNull") ? null : snapshot(args[1]);
                    (onInsertRow ? insertChanges : rowChanges).add(new AbstractMap.SimpleImmutableEntry<>(args[0], value));
                }
                return result;
            }
        }
    }

    // The driver reports no row count here. pgjdbc ignores the count its own UPDATE returns, and H2
    // sends nothing at all for an updateRow with no pending changes, so the outcome is only Done.
    private Object change(Method method, Object[] args, List<Object> written, List<Object> pending) throws Throwable {
        ConnectionHandler connection = statement.connection();
        Step step = connection.next(Step.Kind.STATEMENT, "ResultSet." + method.getName() + "()", written);
        return connection.run(step, () -> {
            Object result = pass(method, args);
            pending.clear();
            return result;
        }, result -> new Outcome.Done());
    }

    @Override
    String describe() {
        return "txrace result set over " + real;
    }
}

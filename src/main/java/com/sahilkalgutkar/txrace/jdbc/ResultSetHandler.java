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

    // Moving the cursor throws away column updates that were not applied yet.
    private static final Set<String> MOVES = Set.of("next", "previous", "first", "last", "absolute", "relative",
            "beforeFirst", "afterLast", "moveToInsertRow", "moveToCurrentRow", "cancelRowUpdates");

    private final StatementHandler statement;
    // Column updates since the last row change, as column=value pairs.
    private final List<Object> changes = new ArrayList<>();

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
            case "updateRow", "insertRow", "deleteRow" -> {
                ConnectionHandler connection = statement.connection();
                Step step = connection.next(Step.Kind.STATEMENT, "ResultSet." + name + "()",
                        name.equals("deleteRow") ? List.of() : changes);
                return connection.run(step, () -> {
                    Object result = pass(method, args);
                    changes.clear();
                    return result;
                }, result -> new Outcome.Updated(1));
            }
            default -> {
                Object result = pass(method, args);
                if (MOVES.contains(name)) {
                    changes.clear();
                } else if (name.startsWith("update") && method.getDeclaringClass() == ResultSet.class) {
                    Object value = name.equals("updateNull") ? null : snapshot(args[1]);
                    changes.add(new AbstractMap.SimpleImmutableEntry<>(args[0], value));
                }
                return result;
            }
        }
    }

    @Override
    String describe() {
        return "txrace result set over " + real;
    }
}

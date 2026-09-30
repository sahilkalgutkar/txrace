package com.sahilkalgutkar.txrace.jdbc;

import com.sahilkalgutkar.txrace.trace.Outcome;
import com.sahilkalgutkar.txrace.trace.Step;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Turns each execute call into a step, remembering the parameters bound so far and the rows added
 * to the batch.
 */
final class StatementHandler extends ProxyHandler {

    private final ConnectionHandler connection;
    private final Statement proxy;
    private final String sql;
    private final SortedMap<Integer, Object> parameters = new TreeMap<>();
    // The driver hands back the same result set on every getResultSet() call, and so should we,
    // or pending column updates end up split across two proxies.
    private ResultSet lastRows;
    private ResultSet lastProxy;
    // SQL strings for a plain statement, parameter lists for a prepared one.
    private final List<Object> batch = new ArrayList<>();

    private <T extends Statement> StatementHandler(Class<T> type, Statement real, ConnectionHandler connection,
            String sql) {
        super(real);
        this.connection = connection;
        this.sql = sql;
        this.proxy = create(type, this);
    }

    /** {@code sql} is the prepared SQL, or null for a plain statement. */
    static <T extends Statement> T wrap(Class<T> type, Statement real, ConnectionHandler connection, String sql) {
        return type.cast(new StatementHandler(type, real, connection, sql).proxy);
    }

    Statement proxy() {
        return proxy;
    }

    ConnectionHandler connection() {
        return connection;
    }

    @Override
    Object handle(Method method, Object[] args) throws Throwable {
        Object result = dispatch(method, args);
        // Result sets have to point back at this proxy, or getStatement() leads out of the trace.
        if (result instanceof ResultSet rows) {
            if (rows != lastRows) {
                lastRows = rows;
                lastProxy = ResultSetHandler.wrap(rows, this);
            }
            return lastProxy;
        }
        return result;
    }

    private Object dispatch(Method method, Object[] args) throws Throwable {
        String name = method.getName();
        switch (name) {
            case "execute", "executeQuery", "executeUpdate", "executeLargeUpdate" -> {
                // With no arguments this is a prepared statement running its own SQL. With SQL
                // passed in, it is a plain statement and there is nothing bound.
                boolean prepared = args == null;
                Step step = connection.next(Step.Kind.STATEMENT,
                        prepared ? sql : (String) args[0], prepared ? bound() : List.of());
                return connection.run(step, () -> pass(method, args), result -> outcome(name, result));
            }
            case "executeBatch", "executeLargeBatch" -> {
                Step step = sql == null
                        ? connection.next(Step.Kind.BATCH, joined(), List.of())
                        : connection.next(Step.Kind.BATCH, sql, batch);
                return connection.run(step, () -> {
                    try {
                        return pass(method, args);
                    } finally {
                        // The driver empties its batch once it has run, whether or not it worked.
                        batch.clear();
                    }
                }, StatementHandler::batchOutcome);
            }
            case "addBatch" -> {
                Object result = pass(method, args);
                batch.add(args == null ? bound() : args[0]);
                return result;
            }
            case "clearBatch" -> batch.clear();
            case "getConnection" -> {
                return connection.proxy();
            }
            case "clearParameters" -> parameters.clear();
            default -> {
                Object result = pass(method, args);
                if (setsParameter(method, args)) {
                    parameters.put((Integer) args[0], name.equals("setNull") ? null : snapshot(args[1]));
                }
                return result;
            }
        }
        return pass(method, args);
    }

    @Override
    String describe() {
        return "txrace statement over " + real;
    }

    private List<Object> bound() {
        return new ArrayList<>(parameters.values());
    }

    private String joined() {
        return batch.stream().map(String.class::cast).collect(Collectors.joining("; "));
    }

    private Outcome outcome(String method, Object result) throws SQLException {
        return switch (method) {
            case "executeQuery" -> new Outcome.Rows();
            case "executeUpdate", "executeLargeUpdate" -> new Outcome.Updated(((Number) result).longValue());
            default -> {
                if ((Boolean) result) {
                    yield new Outcome.Rows();
                }
                int count = ((Statement) real).getUpdateCount();
                yield count == -1 ? new Outcome.Done() : new Outcome.Updated(count);
            }
        };
    }

    private static Outcome batchOutcome(Object result) {
        long[] counts = result instanceof int[] ints ? Arrays.stream(ints).asLongStream().toArray() : (long[]) result;
        return new Outcome.Batch(Arrays.stream(counts).boxed().toList());
    }

    private static boolean setsParameter(Method method, Object[] args) {
        // Indexed setters are all declared on PreparedStatement. Statement's own setters
        // (setFetchSize and friends) and CallableStatement's named ones are left alone.
        return method.getDeclaringClass() == PreparedStatement.class
                && method.getName().startsWith("set")
                && args != null && args.length >= 2
                && method.getParameterTypes()[0] == int.class;
    }
}

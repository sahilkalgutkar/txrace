package com.sahilkalgutkar.txrace.jdbc;

import com.sahilkalgutkar.txrace.trace.Outcome;
import com.sahilkalgutkar.txrace.trace.Step;
import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/** Turns each execute call into a step, remembering the parameters bound so far. */
final class StatementHandler extends ProxyHandler {

    private final ConnectionHandler connection;
    private final String sql;
    private final SortedMap<Integer, Object> parameters = new TreeMap<>();

    private StatementHandler(Statement real, ConnectionHandler connection, String sql) {
        super(real);
        this.connection = connection;
        this.sql = sql;
    }

    /** {@code sql} is the prepared SQL, or null for a plain statement. */
    static <T extends Statement> T wrap(Class<T> type, Statement real, ConnectionHandler connection, String sql) {
        return create(type, new StatementHandler(real, connection, sql));
    }

    @Override
    Object handle(Method method, Object[] args) throws Throwable {
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
            case "getConnection" -> {
                return connection.proxy();
            }
            case "clearParameters" -> parameters.clear();
            default -> {
                if (setsParameter(method, args)) {
                    parameters.put((Integer) args[0], name.equals("setNull") ? null : args[1]);
                }
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

    private static boolean setsParameter(Method method, Object[] args) {
        // Indexed setters are all declared on PreparedStatement. Statement's own setters
        // (setFetchSize and friends) and CallableStatement's named ones are left alone.
        return method.getDeclaringClass() == PreparedStatement.class
                && method.getName().startsWith("set")
                && args != null && args.length >= 2
                && method.getParameterTypes()[0] == int.class;
    }
}

package com.sahilkalgutkar.txrace.jdbc;

import com.sahilkalgutkar.txrace.trace.Outcome;
import com.sahilkalgutkar.txrace.trace.Step;
import com.sahilkalgutkar.txrace.trace.Trace;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Hands out traced statements, sends every step through the gate, and keeps count of the
 * transactions on its connection.
 */
final class ConnectionHandler extends ProxyHandler {

    interface Call {
        Object call() throws Throwable;
    }

    interface Describe {
        Outcome of(Object result) throws SQLException;
    }

    private final int id;
    private final Gate gate;
    private final Trace trace;
    private final Connection proxy;
    private int transaction = 1;
    private boolean autoCommit;
    // True once the current transaction has sent a statement. Only then is there anything for a
    // commit or rollback to end.
    private boolean open;

    private ConnectionHandler(Connection real, int id, Gate gate, Trace trace) throws SQLException {
        super(real);
        this.id = id;
        this.gate = gate;
        this.trace = trace;
        this.autoCommit = real.getAutoCommit();
        this.proxy = create(Connection.class, this);
    }

    static Connection wrap(Connection real, int id, Gate gate, Trace trace) throws SQLException {
        return new ConnectionHandler(real, id, gate, trace).proxy;
    }

    Connection proxy() {
        return proxy;
    }

    @Override
    Object handle(Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "commit" -> {
                return open ? end(Step.Kind.COMMIT, method, args) : pass(method, args);
            }
            case "rollback" -> {
                // rollback(Savepoint) undoes part of the transaction without ending it.
                if (args == null) {
                    return open ? end(Step.Kind.ROLLBACK, method, args) : pass(method, args);
                }
            }
            case "setAutoCommit" -> {
                return setAutoCommit(method, args);
            }
            case "close" -> {
                return close(method, args);
            }
            default -> {
            }
        }
        Object result = pass(method, args);
        Class<?> type = method.getReturnType();
        if (type == Statement.class || type == PreparedStatement.class || type == CallableStatement.class) {
            String sql = type == Statement.class ? null : (String) args[0];
            return StatementHandler.wrap(type.asSubclass(Statement.class), (Statement) result, this, sql);
        }
        return result;
    }

    @Override
    String describe() {
        return "txrace connection " + id + " over " + real;
    }

    private Object end(Step.Kind kind, Method method, Object[] args) throws Throwable {
        return run(next(kind, null, List.of()), () -> pass(method, args), result -> new Outcome.Done());
    }

    private Object setAutoCommit(Method method, Object[] args) throws Throwable {
        boolean on = (Boolean) args[0];
        if (on && open) {
            // Turning autocommit on in the middle of a transaction commits it.
            end(Step.Kind.COMMIT, method, args);
        } else {
            pass(method, args);
        }
        autoCommit = on;
        return null;
    }

    private Object close(Method method, Object[] args) throws Throwable {
        try {
            if (open) {
                // H2, PostgreSQL and connection pools all roll back work left pending at close.
                // Doing it here puts that rollback, and the locks it releases, through the gate.
                Connection connection = (Connection) real;
                run(next(Step.Kind.ROLLBACK, null, List.of()), () -> {
                    connection.rollback();
                    return null;
                }, result -> new Outcome.Done());
            }
        } finally {
            pass(method, args);
        }
        return null;
    }

    Step next(Step.Kind kind, String sql, List<Object> parameters) {
        return new Step(id, transaction, kind, sql, parameters, autoCommit);
    }

    /** Sends one step through the gate to the driver, recording it on the way. */
    Object run(Step step, Call call, Describe describe) throws Throwable {
        gate.before(step);
        int seq = trace.begin(step);
        Object result;
        try {
            result = call.call();
        } catch (Throwable t) {
            finish(step, seq, Outcome.failed(t));
            throw t;
        }
        Outcome outcome;
        try {
            outcome = describe.of(result);
        } catch (SQLException e) {
            // The statement ran. Only the follow-up question about it failed, and that must not
            // reach the caller as if the statement had.
            outcome = new Outcome.Done();
        }
        finish(step, seq, outcome);
        return result;
    }

    private void finish(Step step, int seq, Outcome outcome) {
        trace.end(seq, outcome);
        if (step.endsTransaction()) {
            transaction++;
            open = false;
        } else {
            open = true;
        }
        gate.after(step, outcome);
    }
}

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
                return end(Step.Kind.COMMIT, method, args);
            }
            case "rollback" -> {
                // rollback(Savepoint) undoes part of the transaction without ending it.
                if (args == null) {
                    return end(Step.Kind.ROLLBACK, method, args);
                }
            }
            case "setAutoCommit" -> {
                return setAutoCommit(method, args);
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
        if (on && !autoCommit) {
            // Turning autocommit on in the middle of a transaction commits it.
            end(Step.Kind.COMMIT, method, args);
        } else {
            pass(method, args);
        }
        autoCommit = on;
        return null;
    }

    Step next(Step.Kind kind, String sql, List<Object> parameters) {
        return new Step(id, transaction, kind, sql, parameters);
    }

    /** Sends one step through the gate to the driver, recording it on the way. */
    Object run(Step step, Call call, Describe describe) throws Throwable {
        gate.before(step);
        int seq = trace.begin(step);
        Object result;
        Outcome outcome;
        try {
            result = call.call();
            outcome = describe.of(result);
        } catch (Throwable t) {
            finish(step, seq, Outcome.failed(t));
            throw t;
        }
        finish(step, seq, outcome);
        return result;
    }

    private void finish(Step step, int seq, Outcome outcome) {
        trace.end(seq, outcome);
        // With autocommit on, every statement is a transaction of its own, failed or not.
        if (step.endsTransaction() || autoCommit) {
            transaction++;
        }
        gate.after(step, outcome);
    }
}

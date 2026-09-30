package com.sahilkalgutkar.txrace.jdbc;

import java.lang.reflect.Method;
import java.sql.ResultSet;

/** Keeps a result set pointing back at the traced statement that produced it. */
final class ResultSetHandler extends ProxyHandler {

    private final StatementHandler statement;

    private ResultSetHandler(ResultSet real, StatementHandler statement) {
        super(real);
        this.statement = statement;
    }

    static ResultSet wrap(ResultSet real, StatementHandler statement) {
        return create(ResultSet.class, new ResultSetHandler(real, statement));
    }

    @Override
    Object handle(Method method, Object[] args) throws Throwable {
        if (method.getName().equals("getStatement")) {
            return statement.proxy();
        }
        return pass(method, args);
    }

    @Override
    String describe() {
        return "txrace result set over " + real;
    }
}

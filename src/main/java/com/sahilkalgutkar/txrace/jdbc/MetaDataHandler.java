package com.sahilkalgutkar.txrace.jdbc;

import java.lang.reflect.Method;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

/**
 * Keeps {@code getMetaData().getConnection()} pointing at the traced connection, and the result
 * sets it returns from pointing at the driver's own statement.
 */
final class MetaDataHandler extends ProxyHandler {

    private final ConnectionHandler connection;

    private MetaDataHandler(DatabaseMetaData real, ConnectionHandler connection) {
        super(real);
        this.connection = connection;
    }

    static DatabaseMetaData wrap(DatabaseMetaData real, ConnectionHandler connection) {
        return create(DatabaseMetaData.class, new MetaDataHandler(real, connection));
    }

    @Override
    Object handle(Method method, Object[] args) throws Throwable {
        if (method.getName().equals("getConnection")) {
            return connection.proxy();
        }
        Object result = pass(method, args);
        // JDBC lets a metadata result set answer null from getStatement(). pgjdbc answers with the
        // raw statement it used, which would be a way out of the trace.
        return result instanceof ResultSet rows ? ResultSetHandler.wrap(rows, null) : result;
    }

    @Override
    String describe() {
        return "txrace metadata over " + real;
    }
}

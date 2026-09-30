package com.sahilkalgutkar.txrace.jdbc;

import java.lang.reflect.Method;
import java.sql.DatabaseMetaData;

/** Keeps {@code getMetaData().getConnection()} pointing at the traced connection. */
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
        return pass(method, args);
    }

    @Override
    String describe() {
        return "txrace metadata over " + real;
    }
}

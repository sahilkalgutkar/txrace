package com.sahilkalgutkar.txrace.jdbc;

import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Gives each test that asks for a {@link DataSource} its own in-memory H2 database, and shuts it
 * down afterwards.
 */
public final class H2Extension implements ParameterResolver, AfterEachCallback {

    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(H2Extension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == DataSource.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        JdbcDataSource database = new JdbcDataSource();
        // DB_CLOSE_DELAY=-1 keeps the database alive between connections until SHUTDOWN.
        database.setURL("jdbc:h2:mem:txrace" + NEXT.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        database.setUser("sa");
        context.getStore(NAMESPACE).put("database", database);
        return database;
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        DataSource database = context.getStore(NAMESPACE).get("database", DataSource.class);
        if (database != null) {
            try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }
}

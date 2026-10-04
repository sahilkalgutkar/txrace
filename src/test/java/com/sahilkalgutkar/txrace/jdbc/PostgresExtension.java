package com.sahilkalgutkar.txrace.jdbc;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Gives each test that asks for a {@link DataSource} its own database on an embedded PostgreSQL
 * server. The server starts once per test run, which takes a couple of seconds, and needs no
 * Docker.
 */
public final class PostgresExtension implements ParameterResolver, AfterEachCallback {

    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(PostgresExtension.class);
    private static EmbeddedPostgres server;

    private static synchronized EmbeddedPostgres server() {
        if (server == null) {
            try {
                server = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new UncheckedIOException("could not start embedded PostgreSQL", e);
            }
            EmbeddedPostgres started = server;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    started.close();
                } catch (IOException ignored) {
                    // The JVM is on its way out either way.
                }
            }));
        }
        return server;
    }

    private static void admin(String sql) throws SQLException {
        try (Connection connection = server().getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == DataSource.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        String name = "txrace" + NEXT.incrementAndGet();
        try {
            admin("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create database " + name, e);
        }
        context.getStore(NAMESPACE).put("database", name);
        return server().getDatabase("postgres", name);
    }

    @Override
    public void afterEach(ExtensionContext context) throws SQLException {
        String name = context.getStore(NAMESPACE).get("database", String.class);
        if (name != null) {
            admin("DROP DATABASE " + name + " WITH (FORCE)");
        }
    }
}

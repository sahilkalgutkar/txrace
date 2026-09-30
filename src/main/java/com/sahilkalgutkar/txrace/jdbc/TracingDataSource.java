package com.sahilkalgutkar.txrace.jdbc;

import com.sahilkalgutkar.txrace.trace.Trace;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A data source whose connections send every statement, commit and rollback through a
 * {@link Gate} and record it in a {@link Trace}.
 *
 * <p>Closing a connection with work still pending rolls that work back as a step of its own,
 * rather than leaving it to the driver.
 *
 * <p>Code that unwraps a connection to its driver class and uses that directly is not traced.
 */
public final class TracingDataSource implements DataSource {

    private final DataSource delegate;
    private final Gate gate;
    private final Trace trace = new Trace();
    private final AtomicInteger opened = new AtomicInteger();

    public TracingDataSource(DataSource delegate) {
        this(delegate, Gate.OPEN);
    }

    public TracingDataSource(DataSource delegate, Gate gate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    public Trace trace() {
        return trace;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return wrap(delegate.getConnection(user, password));
    }

    private Connection wrap(Connection real) throws SQLException {
        return ConnectionHandler.wrap(real, opened.incrementAndGet(), gate, trace);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return type.isInstance(this) || delegate.isWrapperFor(type);
    }
}

package com.sahilkalgutkar.txrace.jdbc;

import com.sahilkalgutkar.txrace.trace.Outcome;
import com.sahilkalgutkar.txrace.trace.Step;
import java.sql.SQLException;

/**
 * Every step passes through the gate twice: before it is sent to the driver and after the driver
 * has answered.
 *
 * <p>{@link #before} may block, which is how a scheduler holds a transaction back until its turn.
 * If it throws, the step is never sent and the caller sees the exception. {@link #after} runs on
 * the same thread once the step is over and should not throw.
 */
public interface Gate {

    Gate OPEN = new Gate() {};

    default void before(Step step) throws SQLException {}

    default void after(Step step, Outcome outcome) {}
}

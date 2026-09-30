package com.sahilkalgutkar.txrace.schedule;

/** How one transaction in a run ended. */
public sealed interface Result {

    /** The transaction returned {@code value} and its commit went through. */
    record Committed(Object value) implements Result {}

    /** The transaction, or its commit, threw {@code cause}, and it was rolled back. */
    record RolledBack(Throwable cause) implements Result {}
}

package com.sahilkalgutkar.txrace.schedule;

import java.sql.Connection;

/** The work of one transaction, run on a connection of its own. */
@FunctionalInterface
public interface Transaction {

    /**
     * The scheduler turns autocommit off before calling this, commits once it returns, and rolls
     * back if it throws. Whatever it returns ends up in the run's results.
     */
    Object run(Connection connection) throws Exception;
}

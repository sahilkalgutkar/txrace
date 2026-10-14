package com.sahilkalgutkar.txrace.zoo;

import com.sahilkalgutkar.txrace.schedule.Explorer;
import com.sahilkalgutkar.txrace.schedule.Transaction;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;

/**
 * A concurrency bug the way it turns up in application code, kept next to the same code fixed.
 * Each twin is judged by the serial orders of its own transactions, so neither needs a rule
 * written down for what the data should look like.
 */
interface Specimen {

    /** One version of the code: the state each run starts from, and the transactions that race. */
    record Twin(Explorer.Setup reset, List<Transaction> transactions) {
    }

    /** Creates the tables, once per database. */
    void create(Statement statement) throws SQLException;

    /** What a run left behind, judged along with what each transaction returned. */
    Object observe(DataSource database) throws SQLException;

    Twin buggy();

    Twin fixed();
}

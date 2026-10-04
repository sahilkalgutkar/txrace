package com.sahilkalgutkar.txrace.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A test and its @BeforeEach method have to see the same database, or setup goes somewhere else. */
@ExtendWith(PostgresExtension.class)
class PostgresExtensionTest {

    private String setUp;

    private static String url(DataSource database) throws SQLException {
        try (Connection connection = database.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }

    @BeforeEach
    void remember(DataSource database) throws SQLException {
        setUp = url(database);
    }

    @Test
    void givesTheTestTheDatabaseItsSetupUsed(DataSource database) throws SQLException {
        assertThat(url(database)).isEqualTo(setUp);
    }
}

package com.sahilkalgutkar.txrace.schedule;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import javax.sql.DataSource;

/** A real database behind connections that name some other product, which the lock watch cannot ask. */
final class OtherDatabase {

    private OtherDatabase() {
    }

    static DataSource over(DataSource real) {
        DatabaseMetaData other = (DatabaseMetaData) Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class},
                (proxy, method, args) -> method.getName().equals("getDatabaseProductName") ? "Other" : null);
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Connection connection = real.getConnection();
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (p, m, a) -> m.getName().equals("getMetaData") ? other : m.invoke(connection, a));
                });
    }
}

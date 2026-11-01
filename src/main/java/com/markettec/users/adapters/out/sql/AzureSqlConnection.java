package com.markettec.users.adapters.out.sql;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;

import java.sql.SQLException;

/** Uses typed driver properties, with no password, SQL user or concatenated JDBC URL. */
public final class AzureSqlConnection {
    private AzureSqlConnection() { }

    public static SQLServerDataSource dataSource(String server, String database, int port) throws SQLException {
        SQLServerDataSource source = new SQLServerDataSource();
        source.setServerName(server);
        source.setDatabaseName(database);
        source.setPortNumber(port);
        source.setAuthentication("ActiveDirectoryDefault");
        source.setEncrypt("true");
        source.setTrustServerCertificate(false);
        source.setHostNameInCertificate("*.database.windows.net");
        source.setLoginTimeout(30);
        source.setSocketTimeout(30000);
        source.setApplicationName("ms-users");
        return source;
    }
}

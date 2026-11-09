package com.markettec.users.adapters.out.sql;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;

import java.sql.SQLException;

/** Uses typed driver properties, with no password, SQL user or concatenated JDBC URL. */
public final class AzureSqlConnection {
    private AzureSqlConnection() { }

    public static SQLServerDataSource dataSource(String server, String database, int port) throws SQLException {
        return dataSource(server, database, port, "ActiveDirectoryDefault");
    }

    /** Runtime on Azure: no developer credential chain or password fallback. */
    public static SQLServerDataSource managedIdentityDataSource(String server, String database, int port) throws SQLException {
        return dataSource(server, database, port, "ActiveDirectoryManagedIdentity");
    }

    private static SQLServerDataSource dataSource(String server, String database, int port, String authentication)
            throws SQLException {
        SQLServerDataSource source = connectionProperties(server, database, port);
        source.setAuthentication(authentication);
        return source;
    }

    static SQLServerDataSource operatorDataSource(String server, String database, int port, String token) {
        SQLServerDataSource source = connectionProperties(server, database, port);
        source.setAccessToken(token);
        return source;
    }

    private static SQLServerDataSource connectionProperties(String server, String database, int port) {
        SQLServerDataSource source = new SQLServerDataSource();
        source.setServerName(server);
        source.setDatabaseName(database);
        source.setPortNumber(port);
        source.setEncrypt("true");
        source.setTrustServerCertificate(false);
        source.setHostNameInCertificate("*.database.windows.net");
        source.setLoginTimeout(30);
        source.setSocketTimeout(30000);
        source.setApplicationName("ms-users");
        return source;
    }
}

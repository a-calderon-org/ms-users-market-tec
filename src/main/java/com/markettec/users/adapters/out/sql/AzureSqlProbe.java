package com.markettec.users.adapters.out.sql;

import java.sql.SQLException;

/** Explicit read-only diagnostic; never starts Spring or Flyway and never prints credentials. */
public final class AzureSqlProbe {
    private AzureSqlProbe() { }

    public static void main(String[] args) throws Exception {
        String database = "sqldb-ms-users-dev";
        var source = AzureSqlConnection.dataSource("sql-markettec-dev.database.windows.net", database, 1433);
        try (var connection = source.getConnection(); var query = connection.createStatement()) {
            query.setQueryTimeout(20);
            try (var row = query.executeQuery("""
                    SELECT DB_NAME() AS database_name, SUSER_SNAME() AS login_name, USER_NAME() AS database_user,
                        CONVERT(varchar(128), DATABASEPROPERTYEX(DB_NAME(), 'Collation')) AS database_collation,
                        CASE WHEN USER_NAME()='dbo' THEN 1 ELSE 0 END AS is_dbo,
                        IS_MEMBER('db_owner') AS is_db_owner,
                        HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'CREATE TABLE') AS can_create_table,
                        HAS_PERMS_BY_NAME('dbo', 'SCHEMA', 'ALTER') AS can_alter_dbo,
                        HAS_PERMS_BY_NAME('dbo', 'SCHEMA', 'SELECT') AS can_select_dbo,
                        HAS_PERMS_BY_NAME('dbo', 'SCHEMA', 'INSERT') AS can_insert_dbo,
                        HAS_PERMS_BY_NAME('dbo', 'SCHEMA', 'UPDATE') AS can_update_dbo
                    """)) {
                if (!row.next() || !database.equals(row.getString("database_name"))) {
                    throw new IllegalStateException("Unexpected database; stop before any migration");
                }
                System.out.println("database=" + database + "; loginPresent=" + (row.getString("login_name") != null)
                        + "; databaseUserPresent=" + (row.getString("database_user") != null));
                System.out.println("collation=" + row.getString("database_collation"));
                System.out.println("is_dbo=" + row.getInt("is_dbo") + "; is_db_owner=" + row.getInt("is_db_owner"));
                for (String permission : new String[]{"can_create_table", "can_alter_dbo", "can_select_dbo",
                        "can_insert_dbo", "can_update_dbo"}) {
                    System.out.println(permission + "=" + row.getInt(permission));
                }
            }
            try (var rows = query.executeQuery("""
                    SELECT COUNT(*) AS application_tables FROM sys.tables
                    WHERE schema_id = SCHEMA_ID('dbo') AND name IN ('users','profiles','external_identities')
                    """)) {
                rows.next();
                System.out.println("applicationTables=" + rows.getInt(1));
            }
            try (var rows = query.executeQuery("SELECT COUNT(*) FROM sys.tables WHERE name = 'flyway_schema_history'")) {
                rows.next();
                System.out.println("flywayHistoryTables=" + rows.getInt(1));
            }
            System.out.println("Read-only JDBC probe complete; no migrations executed.");
        } catch (SQLException failure) {
            // Driver authentication messages can include account information. Emit codes only.
            System.err.println("JDBC probe failed: SQLState=" + failure.getSQLState()
                    + "; vendorCode=" + failure.getErrorCode());
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                System.err.println("causeType=" + cause.getClass().getSimpleName());
            }
            System.exit(1);
        }
    }
}

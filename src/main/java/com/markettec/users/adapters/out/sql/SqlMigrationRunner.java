package com.markettec.users.adapters.out.sql;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.util.Map;

/**
 * Operator-only Flyway entrypoint for Azure SQL. It is never started by Spring and the App Service
 * runtime never migrates: the runtime identity only validates the schema (see {@link SqlSchemaValidator}).
 *
 * <p>Authentication uses the operator's Entra identity ({@code ActiveDirectoryDefault}, e.g. an
 * {@code az login} session), never the runtime Managed Identity, a SQL password or a JDBC URL.
 * {@code info} is read-only; {@code migrate} requires {@code --confirm-database=<USERS_SQL_DATABASE>}.
 * Output is limited to the database name and migration versions/states.</p>
 */
public final class SqlMigrationRunner {
    static final String AZURE_SQL_SUFFIX = ".database.windows.net";

    enum Command { INFO, MIGRATE }

    record Plan(Command command, String server, String database, int port) { }

    private SqlMigrationRunner() { }

    public static void main(String[] args) {
        int status;
        try {
            Plan plan = plan(args, System.getenv());
            status = run(plan, AzureSqlConnection.dataSource(plan.server(), plan.database(), plan.port()), System.out);
        } catch (IllegalArgumentException refused) {
            System.err.println("Refused: " + refused.getMessage());
            status = 2;
        } catch (Exception failure) {
            // Exception type only: driver messages can include hosts or token diagnostics.
            System.err.println("Migration command failed: " + failure.getClass().getName());
            status = 1;
        }
        System.exit(status);
    }

    static Plan plan(String[] args, Map<String, String> environment) {
        if (args.length == 0) {
            throw new IllegalArgumentException("usage: info | migrate --confirm-database=<USERS_SQL_DATABASE>");
        }
        Command command = switch (args[0]) {
            case "info" -> Command.INFO;
            case "migrate" -> Command.MIGRATE;
            default -> throw new IllegalArgumentException("unknown command");
        };
        String confirmation = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--confirm-database=") && confirmation == null) {
                confirmation = args[i].substring("--confirm-database=".length());
            } else {
                throw new IllegalArgumentException("unknown or repeated argument");
            }
        }
        String server = required(environment, "USERS_SQL_SERVER");
        String database = required(environment, "USERS_SQL_DATABASE");
        if (!server.endsWith(AZURE_SQL_SUFFIX) || server.length() == AZURE_SQL_SUFFIX.length()) {
            throw new IllegalArgumentException("USERS_SQL_SERVER must be an Azure SQL host (*" + AZURE_SQL_SUFFIX + ")");
        }
        int port;
        try {
            port = Integer.parseInt(environment.getOrDefault("USERS_SQL_PORT", "1433"));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("USERS_SQL_PORT must be numeric");
        }
        if (command == Command.MIGRATE && !database.equals(confirmation)) {
            throw new IllegalArgumentException("migrate requires --confirm-database matching USERS_SQL_DATABASE");
        }
        return new Plan(command, server, database, port);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be explicit and non-blank");
        }
        return value.trim();
    }

    /** Same location and schema as the runtime validator; never cleans, baselines or migrates out of order. */
    static FluentConfiguration configuration() {
        return Flyway.configure()
                .locations("classpath:db/migration")
                .defaultSchema("dbo")
                .schemas("dbo")
                .createSchemas(false)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true)
                .validateOnMigrate(true);
    }

    static int run(Plan plan, DataSource source, PrintStream out) {
        String actual = new JdbcTemplate(source).queryForObject("SELECT DB_NAME()", String.class);
        if (!plan.database().equals(actual)) {
            throw new IllegalArgumentException("connected database does not match USERS_SQL_DATABASE");
        }
        Flyway flyway = configuration().dataSource(source).load();
        out.println("database=" + actual);
        if (plan.command() == Command.MIGRATE) {
            var result = flyway.migrate();
            out.println("migrations_executed=" + result.migrationsExecuted);
        }
        int pending = 0;
        for (MigrationInfo migration : flyway.info().all()) {
            out.println("migration version=" + migration.getVersion() + " state=" + migration.getState());
            if (migration.getState() == MigrationState.PENDING) {
                pending++;
            }
        }
        var current = flyway.info().current();
        out.println("current_version=" + (current == null ? "none" : current.getVersion()));
        out.println("pending=" + pending);
        return 0;
    }
}

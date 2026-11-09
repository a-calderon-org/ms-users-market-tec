package com.markettec.users.adapters.out.sql;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlMigrationRunnerTest {
    private static final Map<String, String> AZURE = Map.of(
            "USERS_SQL_SERVER", "example.database.windows.net", "USERS_SQL_DATABASE", "isolated");

    @Test
    void infoIsReadOnlyAndNeedsNoConfirmation() {
        var plan = SqlMigrationRunner.plan(new String[] {"info"}, AZURE);
        assertEquals(SqlMigrationRunner.Command.INFO, plan.command());
        assertEquals(1433, plan.port());
    }

    @Test
    void migrateRequiresTheExactTargetDatabase() {
        assertThrows(IllegalArgumentException.class, () -> SqlMigrationRunner.plan(new String[] {"migrate"}, AZURE));
        assertThrows(IllegalArgumentException.class,
                () -> SqlMigrationRunner.plan(new String[] {"migrate", "--confirm-database=other"}, AZURE));
        assertEquals(SqlMigrationRunner.Command.MIGRATE,
                SqlMigrationRunner.plan(new String[] {"migrate", "--confirm-database=isolated"}, AZURE).command());
    }

    @Test
    void unknownCommandsAndArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SqlMigrationRunner.plan(new String[0], AZURE));
        assertThrows(IllegalArgumentException.class, () -> SqlMigrationRunner.plan(new String[] {"clean"}, AZURE));
        assertThrows(IllegalArgumentException.class, () -> SqlMigrationRunner.plan(new String[] {"repair"}, AZURE));
        assertThrows(IllegalArgumentException.class,
                () -> SqlMigrationRunner.plan(new String[] {"info", "--baseline"}, AZURE));
        assertThrows(IllegalArgumentException.class, () -> SqlMigrationRunner.plan(
                new String[] {"migrate", "--confirm-database=isolated", "--confirm-database=isolated"}, AZURE));
    }

    @Test
    void targetMustBeExplicitAzureSql() {
        assertThrows(IllegalArgumentException.class, () -> SqlMigrationRunner.plan(new String[] {"info"}, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SqlMigrationRunner.plan(new String[] {"info"},
                Map.of("USERS_SQL_SERVER", "localhost", "USERS_SQL_DATABASE", "isolated")));
        assertThrows(IllegalArgumentException.class, () -> SqlMigrationRunner.plan(new String[] {"info"},
                Map.of("USERS_SQL_SERVER", " ", "USERS_SQL_DATABASE", "isolated")));
        assertThrows(IllegalArgumentException.class, () -> SqlMigrationRunner.plan(new String[] {"info"},
                Map.of("USERS_SQL_SERVER", "example.database.windows.net", "USERS_SQL_DATABASE", "isolated",
                        "USERS_SQL_PORT", "x")));
    }

    @Test
    void flywayConfigurationCannotCleanBaselineOrReorder() {
        var configuration = SqlMigrationRunner.configuration();
        assertTrue(configuration.isCleanDisabled());
        assertFalse(configuration.isBaselineOnMigrate());
        assertFalse(configuration.isOutOfOrder());
        assertFalse(configuration.isCreateSchemas());
        assertTrue(configuration.isValidateOnMigrate());
        assertEquals("dbo", configuration.getDefaultSchema());
        assertArrayEquals(new String[] {"dbo"}, configuration.getSchemas());
        assertEquals("classpath:db/migration", configuration.getLocations()[0].getDescriptor());
    }
}

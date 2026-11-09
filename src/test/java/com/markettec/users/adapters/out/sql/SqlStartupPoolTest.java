package com.markettec.users.adapters.out.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** MTEC-37: after a slow resume the schema validation reuses the recovered connection instead of logging in again. */
class SqlStartupPoolTest {
    private final AtomicInteger logins = new AtomicInteger();

    private HikariConfig config(DataSource database) {
        var config = new HikariConfig();
        config.setDataSource(database);
        config.setPoolName("users-sql-test");
        config.setIdleTimeout(60000);
        config.setMaxLifetime(600000);
        config.setKeepaliveTime(0);
        config.setConnectionTimeout(35000);
        return config;
    }

    private DataSource database() throws SQLException {
        var database = mock(DataSource.class);
        when(database.getConnection()).thenAnswer(call -> {
            logins.incrementAndGet();
            var connection = mock(Connection.class);
            when(connection.isValid(anyInt())).thenReturn(true);
            return connection;
        });
        return database;
    }

    @Test
    void runtimeShapeWithMinimumIdleZeroDropsTheFailFastConnection() throws Exception {
        // The defect: the connection recovered by the fail-fast check is closed, validation logs in again.
        var config = config(database());
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        try (var pool = new HikariDataSource(config)) {
            assertEquals(1, logins.get());
            try (var ignored = pool.getConnection()) { assertEquals(2, logins.get()); }
        }
    }

    @Test
    void startupValidationRunsOverTheSingleRecoveredConnectionThenTheRuntimeShapeReturns() throws Exception {
        var config = config(database());
        SqlStartupPool.configureForStartup(config);
        try (var pool = new HikariDataSource(config)) {
            assertEquals(1, logins.get(), "fail-fast connection");
            // Validation: DB_NAME(), Flyway validate (holds its connection) and the catalogue queries, sequentially.
            for (int step = 0; step < 12; step++) {
                try (var connection = pool.getConnection()) {
                    assertNotNull(connection);
                    Thread.sleep(step == 0 ? 250 : 5); // across the housekeeper's first run (100 ms)
                }
            }
            assertEquals(1, logins.get(), "no second login during startup validation");
            assertEquals(1, pool.getHikariPoolMXBean().getTotalConnections());

            SqlStartupPool.startupComplete(pool, 3);
            assertEquals(3, pool.getMaximumPoolSize());
            assertEquals(0, pool.getMinimumIdle(), "an idle database must still be able to auto-pause");
            try (var first = pool.getConnection(); var second = pool.getConnection()) {
                assertNotSame(first, second);
                assertEquals(2, logins.get(), "runtime may grow beyond the startup connection");
            }
        }
    }

    @Test
    void startupCompleteIgnoresOtherAndClosedDataSources() throws Exception {
        var other = mock(DataSource.class);
        SqlStartupPool.startupComplete(other, 3);
        verifyNoInteractions(other);
        var config = config(database());
        SqlStartupPool.configureForStartup(config);
        var pool = new HikariDataSource(config);
        pool.close();
        assertDoesNotThrow(() -> SqlStartupPool.startupComplete(pool, 3));
    }
}

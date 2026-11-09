package com.markettec.users.config;

import com.markettec.users.UsersApplication;
import com.markettec.users.adapters.out.sql.AzureSqlConnection;
import com.markettec.users.adapters.out.sql.SqlSchemaValidator;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MTEC-37: the production `sql-appservice` wiring survives a paused Azure SQL database inside the same JVM.
 * The first connection fails with 40613 (database resuming); the second succeeds. The context starts, the web
 * server only opens after the database answered, and /health plus SQL readiness answer 200.
 */
class SqlColdStartRecoveryTest {
    @Test
    void firstConnectionFailsWhileSqlResumesAndTheSameProcessStartsAndAnswers() throws Exception {
        var database = mock(SQLServerDataSource.class);
        var connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(true);
        var attempts = new AtomicInteger();
        var serving = new AtomicBoolean();
        var servedDuringRetry = new AtomicBoolean();
        when(database.getConnection()).thenAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                servedDuringRetry.set(serving.get());
                throw new SQLException("private-sql-host-password-marker", "S0001", 40613);
            }
            return connection;
        });
        ApplicationListener<WebServerInitializedEvent> opened = event -> serving.set(true);
        try (var connections = mockStatic(AzureSqlConnection.class);
             var schema = mockStatic(SqlSchemaValidator.class)) {
            // The validation borrows the pool like the real one (DB_NAME, Flyway, catalogue), but stays offline.
            schema.when(() -> SqlSchemaValidator.validate(any(), eq("isolated"))).thenAnswer(call -> {
                javax.sql.DataSource pool = call.getArgument(0);
                for (int step = 0; step < 3; step++) {
                    try (var borrowed = pool.getConnection()) { assertNotNull(borrowed); }
                }
                return null;
            });
            connections.when(() -> AzureSqlConnection.managedIdentityDataSource("example.database.windows.net",
                    "isolated", 1433)).thenReturn(database);
            try (var context = new SpringApplicationBuilder(UsersApplication.class).listeners(opened)
                    .profiles("sql-appservice")
                    .properties("server.port=0", "management.server.port=0", "USERS_SQL_SERVER=example.database.windows.net",
                            "USERS_SQL_DATABASE=isolated", "USERS_SQL_PORT=1433", "USERS_SQL_POOL_SIZE=3",
                            "users.sql.readiness.cache-ttl=0s", "USERS_CSRF_ACTIVE_KID=sql-test",
                            "USERS_CSRF_KEYS_JSON={\"sql-test\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}")
                    .run()) {
                assertTrue(serving.get());
                assertFalse(servedDuringRetry.get(), "HTTP opened before the SQL retry finished");
                // Exactly one failed and one successful login; the schema was validated once, after recovery.
                schema.verify(() -> SqlSchemaValidator.validate(any(), eq("isolated")), times(1));
                // The recovered connection is the one the validation used: no third login before HTTP.
                assertEquals(2, attempts.get());
                var pool = context.getBean(com.zaxxer.hikari.HikariDataSource.class);
                assertEquals(3, pool.getMaximumPoolSize());
                assertEquals(0, pool.getMinimumIdle());
                connections.verify(() -> AzureSqlConnection.dataSource(any(), any(), anyInt()), never());

                var http = HttpClient.newHttpClient();
                var env = context.getEnvironment();
                var health = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + env.getProperty("local.server.port") + "/health")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, health.statusCode());
                var readiness = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + env.getProperty("local.management.port") + "/actuator/health/readiness")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, readiness.statusCode());
                assertEquals("{\"status\":\"UP\"}", readiness.body());
                assertEquals(2, attempts.get(), "readiness reused the startup connection");
            }
        }
    }
}

package com.markettec.users.adapters.in.web;

import com.markettec.users.adapters.out.sql.SqlUserStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "spring.profiles.active=sql-integration",
        "users.sql.readiness.cache-ttl=0s", "users.csrf.active-kid=sql-test",
        "users.csrf.keys-json={\"sql-test\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}"})
class SqlReadinessHttpTest {
    @Autowired TestRestTemplate http;
    @Autowired ApplicationAvailability availability;
    @MockitoBean DataSource source;
    // Replace only schema/store bootstrap. The production JDBC health bean and SQL profile run unchanged.
    @MockitoBean SqlUserStore store;
    @Value("${local.management.port}") int managementPort;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void databaseOutageAfterStartupReturns503AndRecoveryReturns200(boolean failOnBorrow) throws Exception {
        var available = new AtomicBoolean(true);
        var connection = mock(Connection.class);
        when(source.getConnection()).thenAnswer(call -> {
            if (!available.get() && failOnBorrow) throw new SQLException("private-sql-host-password-marker");
            return connection;
        });
        when(connection.isValid(2)).thenAnswer(call -> available.get());

        assertReadiness(200, "UP");
        available.set(false);
        assertReadiness(503, "DOWN");
        // A real JDBC failure changes the health aggregate, never Spring's lifecycle state.
        assertEquals(ReadinessState.ACCEPTING_TRAFFIC, availability.getReadinessState());
        verify(source, times(2)).getConnection();
        for (int i = 0; i < 3; i++) {
            assertEquals(200, http.getForEntity(internal("liveness"), String.class).getStatusCode().value());
            assertEquals(200, http.getForEntity("/health", String.class).getStatusCode().value());
        }
        verify(source, times(2)).getConnection();
        available.set(true);
        assertReadiness(200, "UP");
        verify(source, times(3)).getConnection();
        verify(connection, times(failOnBorrow ? 2 : 3)).close();
    }

    private void assertReadiness(int status, String health) {
        var response = http.getForEntity(internal("readiness"), Map.class);
        assertEquals(status, response.getStatusCode().value());
        assertEquals(Map.of("status", health), response.getBody());
    }

    private String internal(String group) {
        return "http://127.0.0.1:" + managementPort + "/actuator/health/" + group;
    }
}

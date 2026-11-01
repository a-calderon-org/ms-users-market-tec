package com.markettec.users.adapters.out.sql;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlReadinessHealthIndicatorTest {
    @Test
    void cachesBothUpAndDownUntilExpiryAndReleasesConnections() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true, false, true);
        var ticker = new AtomicLong();
        var indicator = new SqlReadinessHealthIndicator(source, Duration.ofSeconds(30), ticker::get);
        assertEquals(Status.UP, indicator.health().getStatus());
        ticker.set(Duration.ofSeconds(30).toNanos() - 1);
        assertEquals(Status.UP, indicator.health().getStatus());
        verify(source).getConnection();
        ticker.incrementAndGet();
        assertEquals(Status.DOWN, indicator.health().getStatus());
        assertEquals(Status.DOWN, indicator.health().getStatus());
        verify(source, times(2)).getConnection();
        ticker.addAndGet(Duration.ofSeconds(30).toNanos());
        assertEquals(Status.UP, indicator.health().getStatus());
        verify(source, times(3)).getConnection();
        verify(connection, times(3)).close();
    }

    @Test
    void concurrentHealthReadersShareOneJdbcValidation() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var validating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(source.getConnection()).thenAnswer(call -> {
            validating.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return connection;
        });
        when(connection.isValid(2)).thenReturn(true);
        var indicator = new SqlReadinessHealthIndicator(source, Duration.ofSeconds(30), () -> 0L);
        var start = new CyclicBarrier(8);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Status>>();
            for (int i = 0; i < 8; i++) results.add(workers.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return indicator.health().getStatus();
            }));
            try { assertTrue(validating.await(10, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            for (var result : results) assertEquals(Status.UP, result.get(10, TimeUnit.SECONDS));
        }
        verify(source).getConnection();
        verify(connection).isValid(2);
        verify(connection).close();
    }

    @Test
    void jdbcAndClosedPoolFailuresAreDownWithoutPrivateDetails() throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("private-jdbc-secret"),
                new IllegalStateException("private-pool-secret"));
        var indicator = new SqlReadinessHealthIndicator(source, Duration.ZERO);
        for (int i = 0; i < 2; i++) {
            var result = indicator.health();
            assertEquals(Status.DOWN, result.getStatus());
            assertTrue(result.getDetails().isEmpty());
            assertFalse(result.toString().contains("private"));
        }
    }

    @Test
    void rejectsNegativeCacheTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqlReadinessHealthIndicator(mock(DataSource.class), Duration.ofSeconds(-1)));
    }
}

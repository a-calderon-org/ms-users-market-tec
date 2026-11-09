package com.markettec.users.adapters.out.sql;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.markettec.users.config.SafeJsonLogFormatter;
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** MTEC-37: bounded in-process retry of the first SQL connection while Azure SQL serverless resumes. */
class SqlStartupRetryTest {
    private static final String MARKER = "private-sql-host-password-marker";
    private static final SqlStartupRetry.Policy POLICY = SqlStartupRetry.Policy.AZURE_SQL_RESUME;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SqlStartupRetry.class);
    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong();

    @BeforeEach void captureLogs() { logs.start(); logger.addAppender(logs); }
    @AfterEach void releaseLogs() { logger.detachAppender(logs); }

    /** Hikari's fail-fast pool construction wraps the driver failure exactly like this. */
    private static RuntimeException poolFailure(SQLException cause) { return new PoolInitializationException(cause); }

    private static SQLException resuming() { return new SQLException(MARKER, "S0001", 40613); }

    private <T> T run(SqlStartupRetry.Policy policy, Supplier<T> step) {
        return SqlStartupRetry.run(policy, step, delay -> { sleeps.add(delay); clock.addAndGet(delay.toNanos()); },
                clock::get);
    }

    @Test
    void pausedDatabaseIsRetriedInProcessAndTheStartupStepSucceedsOnceItResumes() {
        var calls = new AtomicInteger();
        String result = run(POLICY, () -> {
            if (calls.incrementAndGet() <= 2) throw poolFailure(resuming());
            return "pool";
        });
        assertEquals("pool", result);
        assertEquals(3, calls.get());
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4)), sleeps);
    }

    @Test
    void attemptsAreBoundedBackoffIsCappedAndTheLastFailureIsRethrown() {
        var calls = new AtomicInteger();
        var thrown = assertThrows(PoolInitializationException.class, () -> run(POLICY, () -> {
            calls.incrementAndGet();
            throw poolFailure(new SQLException(MARKER, "08S01", 0));
        }));
        assertEquals(POLICY.maxAttempts(), calls.get());
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofSeconds(10)),
                sleeps);
        assertInstanceOf(SQLException.class, thrown.getCause());
    }

    @Test
    void defaultPolicyEndsInsideTheStagingStartupProbeWindow() {
        // Container Apps Startup probe in staging: initialDelay 1 s + 30 failures x 3 s period.
        Duration probeWindow = Duration.ofSeconds(1 + 30 * 3);
        Duration perAttempt = Duration.ofSeconds(31); // driver loginTimeout (30 s) plus Hikari fail-fast pause
        assertTrue(POLICY.maxElapsed().plus(perAttempt).compareTo(probeWindow.minusSeconds(5)) <= 0);
        var calls = new AtomicInteger();
        assertThrows(PoolInitializationException.class, () -> run(POLICY, () -> {
            calls.incrementAndGet();
            clock.addAndGet(perAttempt.toNanos());
            throw poolFailure(new SQLException(MARKER, "08S01", 0));
        }));
        assertEquals(2, calls.get()); // 0-31 s and 33-64 s; a third attempt would start after 50 s
    }

    @Test
    void noNewAttemptStartsOnceTheElapsedBudgetWouldBeExceeded() {
        var policy = new SqlStartupRetry.Policy(50, Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(150));
        var calls = new AtomicInteger();
        assertThrows(PoolInitializationException.class, () -> run(policy, () -> {
            calls.incrementAndGet();
            clock.addAndGet(Duration.ofSeconds(31).toNanos()); // each attempt runs into the 30 s login timeout
            throw poolFailure(resuming());
        }));
        // 0-31, 33-64, 68-99, 107-138, 148-179: a sixth attempt would start after 150 s.
        assertEquals(5, calls.get());
        assertTrue(Duration.ofNanos(clock.get()).compareTo(Duration.ofSeconds(150 + 31)) <= 0);
    }

    static Stream<RuntimeException> permanentFailures() {
        return Stream.of(
                poolFailure(new SQLException("Login failed", "S0001", 18456)),
                poolFailure(new SQLException("Managed identity unavailable")),
                poolFailure(new SQLException("Cannot open database", "S0001", 4060)),
                new IllegalStateException("Select exactly one persistence profile"),
                new PoolInitializationException(new IllegalStateException("no SQL cause")));
    }

    @ParameterizedTest
    @MethodSource("permanentFailures")
    void nonTransientFailuresFailOnTheFirstAttemptAsBefore(RuntimeException failure) {
        var calls = new AtomicInteger();
        var thrown = assertThrows(RuntimeException.class, () -> run(POLICY, () -> {
            calls.incrementAndGet();
            throw failure;
        }));
        assertSame(failure, thrown);
        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void transientClassification() {
        assertTrue(SqlStartupRetry.isTransient(resuming()));
        assertTrue(SqlStartupRetry.isTransient(new SQLException("x", "08S01", 0)));
        assertTrue(SqlStartupRetry.isTransient(new SQLException("x", "08001", 0)));
        assertTrue(SqlStartupRetry.isTransient(new SQLTransientConnectionException("x")));
        assertTrue(SqlStartupRetry.isTransient(new SQLException("x", "S0001", 40197)));
        assertFalse(SqlStartupRetry.isTransient(new SQLException("x", "S0001", 18456)));
        assertFalse(SqlStartupRetry.isTransient(new SQLException("x", "42S02", 208)));
        assertFalse(SqlStartupRetry.isTransient(new SQLException("x")));
    }

    @Test
    void interruptionStopsRetryingAndKeepsTheInterruptFlag() {
        var failure = poolFailure(resuming());
        try {
            assertSame(failure, assertThrows(PoolInitializationException.class, () -> SqlStartupRetry.run(POLICY,
                    () -> { throw failure; }, delay -> { throw new InterruptedException(); }, clock::get)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /** Writes are never wrapped: only the startup datasource wiring may use the retry. */
    @Test
    void onlyTheStartupDatasourceUsesTheRetry() {
        var classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(com.tngtech.archunit.core.importer.ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.markettec.users");
        var users = classes.get(SqlStartupRetry.class).getDirectDependenciesToSelf().stream()
                .map(d -> d.getOriginClass().getName()).filter(n -> !n.startsWith(SqlStartupRetry.class.getName()))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("com.markettec.users.config.SqlUserConfig"), users);
    }

    @Test
    void retryLogsCarryCodesAndTimingsButNeverDriverMessages() throws Exception {
        var calls = new AtomicInteger();
        run(POLICY, () -> {
            if (calls.incrementAndGet() == 1) throw poolFailure(resuming());
            return "pool";
        });
        var formatter = new SafeJsonLogFormatter();
        var json = new ObjectMapper();
        List<JsonNode> lines = new ArrayList<>();
        for (var event : logs.list) {
            String line = formatter.format(event);
            assertFalse(line.contains(MARKER), line);
            lines.add(json.readTree(line));
        }
        assertEquals(2, lines.size());
        JsonNode retry = lines.get(0);
        assertEquals("sql_startup_retry", retry.get("message").asText());
        assertEquals("retry_scheduled", retry.get("outcome").asText());
        assertEquals(1, retry.get("attempt").asInt());
        assertEquals("S0001", retry.get("sqlState").asText());
        assertEquals(40613, retry.get("vendorCode").asInt());
        assertEquals(2000, retry.get("delayMs").asLong());
        assertNull(retry.get("exception_type"));
        JsonNode recovered = lines.get(1);
        assertEquals("recovered", recovered.get("outcome").asText());
        assertEquals(2, recovered.get("attempt").asInt());
        assertEquals(2000, recovered.get("elapsedMs").asLong());
    }
}

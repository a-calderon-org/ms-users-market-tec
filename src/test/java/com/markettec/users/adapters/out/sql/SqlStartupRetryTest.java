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

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
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
        // Container Apps Startup probe in staging: initialDelay 1 s + 30 failures x 3 s period, from JVM start.
        Duration probeWindow = Duration.ofSeconds(1 + 30 * 3);
        Duration perAttempt = Duration.ofSeconds(31); // driver loginTimeout (30 s) plus Hikari fail-fast pause
        Duration validation = SqlStartupRetry.Policy.VALIDATION_RESERVE;
        assertEquals(probeWindow, SqlStartupRetry.Policy.STARTUP_PROBE_WINDOW);
        assertTrue(POLICY.maxElapsed().plus(perAttempt).plus(validation).compareTo(probeWindow.minusSeconds(4)) <= 0);
        // The 97.3 s reproduction: JVM boot (~12 s) is charged too; the last attempt still ends inside the window.
        Duration jvmBoot = Duration.ofSeconds(12);
        var calls = new AtomicInteger();
        assertThrows(PoolInitializationException.class, () -> SqlStartupRetry.run(POLICY, () -> {
            calls.incrementAndGet();
            clock.addAndGet(perAttempt.toNanos());
            throw poolFailure(new SQLException(MARKER, "08S01", 0));
        }, delay -> { sleeps.add(delay); clock.addAndGet(delay.toNanos()); }, clock::get, jvmBoot));
        // 12-43 s and 45-76 s since JVM start; a third would begin after 46 s. 76 s + validation < 91 s.
        assertEquals(2, calls.get());
        assertTrue(jvmBoot.plus(Duration.ofNanos(clock.get())).plus(validation).compareTo(probeWindow) < 0);
    }

    @Test
    void elapsedBudgetIsChargedFromProcessStartNotFromTheBean() {
        var calls = new AtomicInteger();
        String result = SqlStartupRetry.run(POLICY, () -> {
            if (calls.incrementAndGet() <= 3) {
                clock.addAndGet(Duration.ofSeconds(1).toNanos()); // 40613 answers quickly while resuming
                throw poolFailure(resuming());
            }
            return "pool";
        }, delay -> { sleeps.add(delay); clock.addAndGet(delay.toNanos()); }, clock::get, Duration.ofSeconds(10));
        assertEquals("pool", result);
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)), sleeps);
        sleeps.clear();
        clock.set(0);
        calls.set(0);
        // Same failures after a 40 s boot: 40+1+2 = 43 s, a second retry would begin after 46 s, so none starts.
        assertThrows(PoolInitializationException.class, () -> SqlStartupRetry.run(POLICY, () -> {
            calls.incrementAndGet();
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            throw poolFailure(resuming());
        }, delay -> { sleeps.add(delay); clock.addAndGet(delay.toNanos()); }, clock::get, Duration.ofSeconds(40)));
        assertEquals(2, calls.get());
    }

    @Test
    void aBackoffThatRunsLongDoesNotStartARetryOutsideTheBudget() {
        var calls = new AtomicInteger();
        var failure = poolFailure(resuming());
        assertSame(failure, assertThrows(PoolInitializationException.class, () -> SqlStartupRetry.run(POLICY, () -> {
            calls.incrementAndGet();
            throw failure;
        }, delay -> {
            sleeps.add(delay);
            clock.addAndGet(delay.plusSeconds(5).toNanos());
        }, clock::get, Duration.ofSeconds(42))));
        assertEquals(List.of(Duration.ofSeconds(2)), sleeps);
        assertEquals(1, calls.get());
        assertEquals("exhausted", logs.list.getLast().getKeyValuePairs().stream()
                .filter(field -> field.key.equals("outcome")).findFirst().orElseThrow().value);
    }

    /** mssql-jdbc: TDSChannel.enableSSL terminates with SQLState 08S01 and keeps the JSSE failure as the cause. */
    private static SQLException driverTlsFailure(Throwable jsse) {
        return new SQLException(MARKER, "08S01", 0, jsse);
    }

    private static SSLHandshakeException handshake(String message, Throwable cause) {
        var failure = new SSLHandshakeException(message);
        failure.initCause(cause);
        return failure;
    }

    static Stream<SQLException> permanentTlsFailures() {
        return Stream.of(
                // Untrusted chain: PKIX path building failed (JDK ValidatorException is a CertificateException).
                driverTlsFailure(handshake("PKIX path building failed",
                        new CertificateException("unable to find valid certification path", new CertPathBuilderException("x")))),
                // Expired / revoked: CertPathValidatorException under the handshake.
                driverTlsFailure(handshake("validity check failed", new CertPathValidatorException("expired"))),
                // Host name in certificate does not match hostNameInCertificate (driver's trust manager).
                driverTlsFailure(handshake("server name", new CertificateException("Failed to validate the server name"))),
                driverTlsFailure(new SSLPeerUnverifiedException("peer not authenticated")),
                // Negotiation rejected (no common protocol/cipher): configuration, not the network.
                driverTlsFailure(new SSLHandshakeException("No appropriate protocol")),
                // Also when the driver marks the failure as SQLTransientConnectionException.
                new SQLTransientConnectionException(MARKER, "08S01", 0,
                        handshake("PKIX", new CertificateException("untrusted"))));
    }

    @ParameterizedTest
    @MethodSource("permanentTlsFailures")
    void certificateAndTlsNegotiationFailuresAreNeverRetriedEvenWithSqlState08S01(SQLException tls) {
        assertEquals(SqlStartupRetry.Classification.TLS_PERMANENT, SqlStartupRetry.classify(tls));
        assertFalse(SqlStartupRetry.isTransient(tls));
        var calls = new AtomicInteger();
        var failure = poolFailure(tls);
        assertSame(failure, assertThrows(PoolInitializationException.class, () -> run(POLICY, () -> {
            calls.incrementAndGet();
            throw failure;
        })));
        assertEquals(1, calls.get());
        assertTrue(sleeps.isEmpty());
    }

    static Stream<SQLException> recoverableConnectionFailures() {
        return Stream.of(
                // Gateway / resuming database cut the handshake: EOF, reset, timeout during TLS.
                driverTlsFailure(handshake("Remote host terminated the handshake", new EOFException("SSL peer shut down"))),
                driverTlsFailure(handshake("handshake", new SocketException("Connection reset"))),
                driverTlsFailure(new SSLException("Read timed out", new SocketTimeoutException("Read timed out"))),
                // Plain TCP: refused / timed out.
                new SQLException(MARKER, "08S01", 0, new ConnectException("Connection refused")),
                new SQLException(MARKER, "08S01", 0, new SocketTimeoutException("connect timed out")),
                new SQLException(MARKER, "08S01", 0));
    }

    @ParameterizedTest
    @MethodSource("recoverableConnectionFailures")
    void networkInterruptionsIncludingACutHandshakeStayRetryable(SQLException failure) {
        assertEquals(SqlStartupRetry.Classification.TRANSIENT, SqlStartupRetry.classify(failure));
        var calls = new AtomicInteger();
        assertEquals("pool", run(POLICY, () -> {
            if (calls.incrementAndGet() == 1) throw poolFailure(failure);
            return "pool";
        }));
        assertEquals(2, calls.get());
    }

    @Test
    void permanentTlsFailureIsLoggedAsAFixedClassWithoutDriverOrCertificateText() throws Exception {
        String subject = "CN=private-sql-host.database.windows.net";
        var tls = driverTlsFailure(handshake(MARKER + " " + subject, new CertificateException(subject)));
        assertThrows(PoolInitializationException.class, () -> run(POLICY, () -> { throw poolFailure(tls); }));
        assertEquals(1, logs.list.size());
        String line = new SafeJsonLogFormatter().format(logs.list.get(0));
        assertFalse(line.contains(MARKER), line);
        assertFalse(line.contains("private-sql-host"), line);
        JsonNode json = new ObjectMapper().readTree(line);
        assertEquals("sql_startup_retry", json.get("message").asText());
        assertEquals("permanent", json.get("outcome").asText());
        assertEquals("tls_certificate", json.get("failureClass").asText());
        assertEquals("08S01", json.get("sqlState").asText());
        assertEquals(1, json.get("attempt").asInt());
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

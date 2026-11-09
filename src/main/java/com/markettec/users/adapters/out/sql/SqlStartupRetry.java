package com.markettec.users.adapters.out.sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * MTEC-37: bounded in-process wait for an Azure SQL serverless database that is resuming from auto-pause.
 *
 * <p>Only the startup step that opens the first connection is retried, before any HTTP request is served, so no
 * business write can be repeated. Non-transient failures (login denied, missing database, configuration, an
 * untrusted or mismatched TLS certificate) fail on the first attempt. Logs carry SQLState, vendor code and a fixed
 * failure class only, never driver messages.
 */
public final class SqlStartupRetry {
    /** Azure SQL transient connection errors (Microsoft "transient fault" list relevant to login/resume). */
    static final Set<Integer> TRANSIENT_VENDOR_CODES = Set.of(
            40613, // database not currently available (paused / resuming)
            40197, 40501, 40540, 40143, 49918, 49919, 49920, 4221, 10928, 10929, 10053, 10054, 10060, 42108, 42109);

    private static final Logger LOG = LoggerFactory.getLogger(SqlStartupRetry.class);

    /**
     * The whole startup has to fit the staging Startup probe window (initialDelay 1 s + 30 × 3 s ≈ 91 s), counted
     * from JVM start, not from this bean. {@code maxElapsed} is therefore the last moment, since process start, at
     * which a new attempt may begin: 46 s + one attempt bounded by the driver login timeout (30 s) + schema
     * validation over that same connection (reserve 10 s) leaves a margin of ≥ 5 s, so the process gives up on its
     * own instead of being killed mid-attempt or mid-Flyway.
     */
    public record Policy(int maxAttempts, Duration initialDelay, Duration maxDelay, Duration maxElapsed) {
        public static final Duration STARTUP_PROBE_WINDOW = Duration.ofSeconds(1 + 30 * 3);
        public static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(30);
        public static final Duration VALIDATION_RESERVE = Duration.ofSeconds(10);
        public static final Duration MARGIN = Duration.ofSeconds(5);
        public static final Policy AZURE_SQL_RESUME = new Policy(5, Duration.ofSeconds(2), Duration.ofSeconds(10),
                STARTUP_PROBE_WINDOW.minus(LOGIN_TIMEOUT).minus(VALIDATION_RESERVE).minus(MARGIN));

        public Policy {
            if (maxAttempts < 1 || initialDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0
                    || maxElapsed.isNegative()) {
                throw new IllegalArgumentException("Invalid SQL startup retry policy");
            }
        }
    }

    interface Sleeper { void sleep(Duration delay) throws InterruptedException; }

    /** Why a failure is or is not retried; logged as a fixed token. */
    enum Classification { TRANSIENT, TLS_PERMANENT, PERMANENT }

    private SqlStartupRetry() { }

    /**
     * @param alreadyElapsed time the process has already spent before this step (JVM uptime), charged to the budget
     */
    public static <T> T run(Policy policy, Duration alreadyElapsed, Supplier<T> step) {
        return run(policy, step, delay -> Thread.sleep(delay.toMillis()), System::nanoTime, alreadyElapsed);
    }

    static <T> T run(Policy policy, Supplier<T> step, Sleeper sleeper, LongSupplier nanoTime) {
        return run(policy, step, sleeper, nanoTime, Duration.ZERO);
    }

    static <T> T run(Policy policy, Supplier<T> step, Sleeper sleeper, LongSupplier nanoTime, Duration alreadyElapsed) {
        long start = nanoTime.getAsLong() - Math.max(0L, alreadyElapsed.toNanos());
        Duration delay = policy.initialDelay();
        for (int attempt = 1; ; attempt++) {
            try {
                T result = step.get();
                if (attempt > 1) {
                    LOG.atInfo().addKeyValue("attempt", attempt).addKeyValue("outcome", "recovered")
                            .addKeyValue("elapsedMs", Duration.ofNanos(nanoTime.getAsLong() - start).toMillis())
                            .log("sql_startup_retry");
                }
                return result;
            } catch (RuntimeException failure) {
                SQLException sql = firstSqlCause(failure);
                if (sql == null) throw failure;
                Classification classification = classify(sql);
                Duration elapsed = Duration.ofNanos(nanoTime.getAsLong() - start);
                if (classification != Classification.TRANSIENT) {
                    if (classification == Classification.TLS_PERMANENT) {
                        LOG.atError().addKeyValue("attempt", attempt).addKeyValue("outcome", "permanent")
                                .addKeyValue("failureClass", "tls_certificate")
                                .addKeyValue("sqlState", sql.getSQLState()).addKeyValue("vendorCode", sql.getErrorCode())
                                .addKeyValue("elapsedMs", elapsed.toMillis()).log("sql_startup_retry");
                    }
                    throw failure;
                }
                boolean exhausted = attempt >= policy.maxAttempts()
                        || elapsed.plus(delay).compareTo(policy.maxElapsed()) > 0;
                LOG.atWarn().addKeyValue("attempt", attempt)
                        .addKeyValue("outcome", exhausted ? "exhausted" : "retry_scheduled")
                        .addKeyValue("sqlState", sql.getSQLState()).addKeyValue("vendorCode", sql.getErrorCode())
                        .addKeyValue("delayMs", exhausted ? 0L : delay.toMillis())
                        .addKeyValue("elapsedMs", elapsed.toMillis()).log("sql_startup_retry");
                if (exhausted) throw failure;
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
                Duration doubled = delay.multipliedBy(2);
                delay = doubled.compareTo(policy.maxDelay()) > 0 ? policy.maxDelay() : doubled;
            }
        }
    }

    /** The first SQLException in the cause chain when it is transient, otherwise null. */
    static SQLException transientCause(Throwable failure) {
        SQLException sql = firstSqlCause(failure);
        return sql != null && isTransient(sql) ? sql : null;
    }

    private static SQLException firstSqlCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql;
            if (cause.getCause() == cause) break;
        }
        return null;
    }

    static boolean isTransient(SQLException failure) {
        return classify(failure) == Classification.TRANSIENT;
    }

    static Classification classify(SQLException failure) {
        // mssql-jdbc reports a failed TLS handshake as SQLState 08S01 — the same class as a dropped TCP connection —
        // so the cause chain decides first: a certificate that does not validate will not validate on a retry.
        if (permanentTls(failure)) return Classification.TLS_PERMANENT;
        if (failure instanceof SQLTransientException) return Classification.TRANSIENT;
        if (TRANSIENT_VENDOR_CODES.contains(failure.getErrorCode())) return Classification.TRANSIENT;
        String state = failure.getSQLState();
        // Class 08: connection exception (mssql-jdbc reports TCP failures and login timeouts as 08S01).
        return state != null && state.startsWith("08") ? Classification.TRANSIENT : Classification.PERMANENT;
    }

    /**
     * Permanent: any certificate / path-validation failure ({@link GeneralSecurityException}: CertificateException,
     * CertPathValidatorException, the JDK ValidatorException, the driver's host-name check), an unverified peer, or a
     * handshake rejected by negotiation (protocol, cipher). Recoverable: a handshake cut by the network — its chain
     * carries an EOF / socket reset / timeout, which is what a gateway or a resuming database produces.
     */
    static boolean permanentTls(Throwable failure) {
        boolean handshake = false;
        boolean interruptedIo = false;
        int depth = 0;
        for (Throwable cause = failure; cause != null && depth < 32; cause = cause.getCause(), depth++) {
            if (cause instanceof GeneralSecurityException || cause instanceof SSLPeerUnverifiedException) return true;
            if (cause instanceof SSLHandshakeException) handshake = true;
            if (cause instanceof EOFException || cause instanceof SocketException
                    || cause instanceof InterruptedIOException) interruptedIo = true;
            for (Throwable suppressed : cause.getSuppressed()) {
                if (suppressed instanceof GeneralSecurityException) return true;
            }
            if (cause.getCause() == cause) break;
        }
        return handshake && !interruptedIo;
    }
}

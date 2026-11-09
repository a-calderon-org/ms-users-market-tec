package com.markettec.users.adapters.out.sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * business write can be repeated. Non-transient failures (login denied, missing database, configuration) fail on
 * the first attempt, exactly as before. Logs carry SQLState and vendor code only, never driver messages.
 */
public final class SqlStartupRetry {
    /** Azure SQL transient connection errors (Microsoft "transient fault" list relevant to login/resume). */
    static final Set<Integer> TRANSIENT_VENDOR_CODES = Set.of(
            40613, // database not currently available (paused / resuming)
            40197, 40501, 40540, 40143, 49918, 49919, 49920, 4221, 10928, 10929, 10053, 10054, 10060, 42108, 42109);

    private static final Logger LOG = LoggerFactory.getLogger(SqlStartupRetry.class);

    /**
     * {@code maxElapsed} bounds when a new attempt may start; each attempt is itself bounded by the driver login
     * timeout (30 s). 50 s + 30 s + JVM boot and schema validation stays inside the staging Startup probe window
     * (initialDelay 1 s + 30 × 3 s ≈ 91 s), so the process gives up on its own instead of being killed mid-attempt.
     */
    public record Policy(int maxAttempts, Duration initialDelay, Duration maxDelay, Duration maxElapsed) {
        public static final Policy AZURE_SQL_RESUME =
                new Policy(5, Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofSeconds(50));

        public Policy {
            if (maxAttempts < 1 || initialDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0
                    || maxElapsed.isNegative()) {
                throw new IllegalArgumentException("Invalid SQL startup retry policy");
            }
        }
    }

    interface Sleeper { void sleep(Duration delay) throws InterruptedException; }

    private SqlStartupRetry() { }

    public static <T> T run(Policy policy, Supplier<T> step) {
        return run(policy, step, delay -> Thread.sleep(delay.toMillis()), System::nanoTime);
    }

    static <T> T run(Policy policy, Supplier<T> step, Sleeper sleeper, LongSupplier nanoTime) {
        long start = nanoTime.getAsLong();
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
                SQLException sql = transientCause(failure);
                if (sql == null) throw failure;
                Duration elapsed = Duration.ofNanos(nanoTime.getAsLong() - start);
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
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return isTransient(sql) ? sql : null;
            }
            if (cause.getCause() == cause) break;
        }
        return null;
    }

    static boolean isTransient(SQLException failure) {
        if (failure instanceof SQLTransientException) return true;
        if (TRANSIENT_VENDOR_CODES.contains(failure.getErrorCode())) return true;
        String state = failure.getSQLState();
        // Class 08: connection exception (mssql-jdbc reports TCP failures and login timeouts as 08S01).
        return state != null && state.startsWith("08");
    }
}

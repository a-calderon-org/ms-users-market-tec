package com.markettec.users.adapters.out.sql;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Demand-driven JDBC validation. No scheduler, retries, SQL text, or exception details. */
public final class SqlReadinessHealthIndicator implements HealthIndicator {
    private final DataSource source;
    private final long cacheNanos;
    private final LongSupplier ticker;
    private Health cached;
    private long checkedAt;

    public SqlReadinessHealthIndicator(DataSource source, Duration cacheTtl) {
        this(source, cacheTtl, System::nanoTime);
    }

    SqlReadinessHealthIndicator(DataSource source, Duration cacheTtl, LongSupplier ticker) {
        if (cacheTtl.isNegative()) throw new IllegalArgumentException("SQL health cache TTL must be non-negative");
        this.source = source;
        this.cacheNanos = cacheTtl.toNanos();
        this.ticker = ticker;
    }

    @Override
    public synchronized Health health() {
        if (cached != null && ticker.getAsLong() - checkedAt < cacheNanos) return cached;
        Health result;
        try (var connection = source.getConnection()) {
            result = connection.isValid(2) ? Health.up().build() : Health.down().build();
        } catch (SQLException | RuntimeException unavailable) {
            // Never attach exception messages, JDBC URLs, credentials or hostnames.
            result = Health.down().build();
        }
        cached = result;
        checkedAt = ticker.getAsLong();
        return result;
    }
}

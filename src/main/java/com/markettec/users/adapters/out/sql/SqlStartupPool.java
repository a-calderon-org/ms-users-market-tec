package com.markettec.users.adapters.out.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * MTEC-37: one physical connection for the whole startup.
 *
 * <p>With {@code minimumIdle=0} Hikari's fail-fast check closes the connection it just opened ("initialization
 * check complete and minimumIdle is zero"), so after a slow resume the schema/Flyway validation had to log in a
 * second time. During startup the pool is fixed at exactly one connection: the fail-fast connection stays in the
 * pool and is the one the validation borrows, and the housekeeper cannot add another (total == maximum). Once the
 * validation is complete the runtime shape is restored — {@code minimumIdle=0}, so an idle database can auto-pause
 * again — before any HTTP request is served.
 */
public final class SqlStartupPool {
    private SqlStartupPool() { }

    public static void configureForStartup(HikariConfig config) {
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
    }

    /** Restores the runtime pool shape; a no-op for datasources not built by {@link #configureForStartup}. */
    public static void startupComplete(DataSource source, int maximumPoolSize) {
        if (source instanceof HikariDataSource pool && !pool.isClosed()) {
            var runtime = pool.getHikariConfigMXBean();
            runtime.setMaximumPoolSize(maximumPoolSize);
            runtime.setMinimumIdle(0);
        }
    }
}

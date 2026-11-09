package com.markettec.users.config;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.sql.AzureSqlConnection;
import com.markettec.users.adapters.out.sql.SqlSchemaValidator;
import com.markettec.users.adapters.out.sql.SqlStartupPool;
import com.markettec.users.adapters.out.sql.SqlStartupRetry;
import com.markettec.users.adapters.out.sql.SqlUserStore;
import com.markettec.users.adapters.out.sql.SqlSessionRepository;
import com.markettec.users.adapters.out.sql.SqlRecentAttemptRepository;
import com.markettec.users.adapters.out.sql.SqlGoogleLoginIntentRepository;
import com.markettec.users.adapters.out.sql.SqlPendingGoogleRegistrationRepository;
import com.markettec.users.application.session.*;
import com.markettec.users.application.ports.in.UserUseCases;
import com.markettec.users.application.ports.out.IdentityLinkAuthorization;
import com.markettec.users.application.usecases.UserService;
import com.markettec.users.domain.model.UserDomainException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Clock;
import java.security.SecureRandom;

@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
public class SqlUserConfig {
    @Bean(destroyMethod = "close")
    @Profile("sql-dev | sql-appservice")
    HikariDataSource sqlDataSource(Environment environment,
            @Value("${users.sql.server}") String server, @Value("${users.sql.database}") String database,
            @Value("${users.sql.port}") int port, @Value("${users.sql.maximum-pool-size}") int poolSize) throws SQLException {
        if (environment.acceptsProfiles(Profiles.of("local", "in-memory", "sql-integration"))) {
            throw new IllegalStateException("Select exactly one persistence profile");
        }
        boolean appService = environment.acceptsProfiles(Profiles.of("sql-appservice"));
        if (appService && environment.acceptsProfiles(Profiles.of("sql-dev"))) {
            throw new IllegalStateException("Select exactly one persistence profile");
        }
        if (server == null || server.isBlank() || database == null || database.isBlank()) {
            throw new IllegalArgumentException("SQL server and database must be explicit and non-blank");
        }
        HikariConfig config = new HikariConfig();
        config.setDataSource(appService
                ? AzureSqlConnection.managedIdentityDataSource(server, database, port)
                : AzureSqlConnection.dataSource(server, database, port));
        config.setPoolName("users-sql");
        // MTEC-37: exactly one connection until the schema validation in sqlUserStore completes; the runtime shape
        // (maximum poolSize, minimumIdle 0) is restored there, before HTTP opens.
        SqlStartupPool.configureForStartup(config);
        config.setIdleTimeout(60000);
        config.setMaxLifetime(600000);
        config.setKeepaliveTime(0);
        config.setConnectionTimeout(35000);
        // MTEC-37: Hikari still fails fast (one connection attempt per pool construction). A transient failure of
        // that first connection — a serverless database resuming from auto-pause — is retried here, bounded, instead
        // of exiting the JVM and waiting for a container restart. Nothing serves HTTP until this bean completes.
        // The budget is charged from JVM start, since that is when the Startup probe window begins.
        return SqlStartupRetry.run(SqlStartupRetry.Policy.AZURE_SQL_RESUME,
                Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()), () -> new HikariDataSource(config));
    }

    @Bean
    SqlUserStore sqlUserStore(DataSource source, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("local", "in-memory"))) {
            throw new IllegalStateException("Select exactly one persistence profile");
        }
        // Runs over the single connection the pool kept from its fail-fast check: no second login after a resume.
        SqlSchemaValidator.validate(source, environment.getRequiredProperty("users.sql.database"));
        if (!environment.acceptsProfiles(Profiles.of("sql-integration"))) {
            SqlStartupPool.startupComplete(source, environment.getRequiredProperty("users.sql.maximum-pool-size", Integer.class));
        }
        return new SqlUserStore(new JdbcTemplate(source), new DataSourceTransactionManager(source));
    }

    @Bean
    IdentityLinkAuthorization sqlIdentityLinkAuthorization() {
        return (principal, target, identity) -> {
            throw new UserDomainException(UserDomainException.Reason.UNAUTHORIZED);
        };
    }

    @Bean
    UserUseCases sqlUserUseCases(SqlUserStore store, IdentityLinkAuthorization authorization, Clock clock) {
        return new UserService(store, store, new UuidUserIdGenerator(), store, authorization, clock);
    }

    @Bean
    SqlSessionRepository sqlSessionRepository(DataSource source) {
        return new SqlSessionRepository(new JdbcTemplate(source));
    }

    @Bean
    SqlRecentAttemptRepository sqlRecentAttemptRepository(DataSource source) {
        return new SqlRecentAttemptRepository(new JdbcTemplate(source));
    }

    @Bean
    SqlGoogleLoginIntentRepository sqlGoogleLoginIntentRepository(DataSource source) {
        return new SqlGoogleLoginIntentRepository(new JdbcTemplate(source));
    }

    @Bean
    SqlPendingGoogleRegistrationRepository sqlPendingGoogleRegistrationRepository(DataSource source) {
        return new SqlPendingGoogleRegistrationRepository(new JdbcTemplate(source));
    }

    @Bean
    RecentAuthenticationAuthority recentAuthenticationAuthority(SessionAuthority sessions,
            SqlRecentAttemptRepository attempts, Clock clock) {
        return new RecentAuthenticationAuthority(sessions, attempts, clock, new SecureRandom());
    }

    @Bean
    SessionActionAuthorization sessionActionAuthorization() {
        return new SessionActionAuthorization() {
            @Override public void requireRenewal(Session session) { throw new SessionRejectedException(); }
            @Override public void requireAdmissibleActivity(Session session) { throw new SessionRejectedException(); }
        };
    }

    @Bean
    com.markettec.users.application.session.CsrfKeys csrfKeys(
            @org.springframework.beans.factory.annotation.Value("${users.csrf.keys-json:}") String keys,
            @org.springframework.beans.factory.annotation.Value("${users.csrf.active-kid:}") String activeKid) {
        return com.markettec.users.application.session.CsrfKeys.fromJson(keys, activeKid);
    }

    @Bean
    SessionAuthority sessionAuthority(SqlSessionRepository sessions, SqlUserStore users,
            SessionActionAuthorization authorization, Clock clock, com.markettec.users.application.session.CsrfKeys csrfKeys) {
        return new SessionAuthority(sessions, users, users, users, clock, new SecureRandom(), authorization, csrfKeys);
    }
}

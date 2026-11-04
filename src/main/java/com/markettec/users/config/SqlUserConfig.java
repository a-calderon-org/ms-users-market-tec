package com.markettec.users.config;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.sql.AzureSqlConnection;
import com.markettec.users.adapters.out.sql.SqlSchemaValidator;
import com.markettec.users.adapters.out.sql.SqlUserStore;
import com.markettec.users.adapters.out.sql.SqlSessionRepository;
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
import java.sql.SQLException;
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
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(0);
        config.setIdleTimeout(60000);
        config.setMaxLifetime(600000);
        config.setKeepaliveTime(0);
        config.setConnectionTimeout(35000);
        return new HikariDataSource(config);
    }

    @Bean
    SqlUserStore sqlUserStore(DataSource source, Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("local", "in-memory"))) {
            throw new IllegalStateException("Select exactly one persistence profile");
        }
        SqlSchemaValidator.validate(source, environment.getRequiredProperty("users.sql.database"));
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
    SessionActionAuthorization sessionActionAuthorization() {
        return new SessionActionAuthorization() {
            @Override public void requireRenewal(Session session) { throw new SessionRejectedException(); }
            @Override public void requireAdmissibleActivity(Session session) { throw new SessionRejectedException(); }
        };
    }

    @Bean
    SessionAuthority sessionAuthority(SqlSessionRepository sessions, SqlUserStore users,
            SessionActionAuthorization authorization, Clock clock) {
        return new SessionAuthority(sessions, users, users, users, clock, new SecureRandom(), authorization);
    }
}

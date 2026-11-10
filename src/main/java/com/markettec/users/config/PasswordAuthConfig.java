package com.markettec.users.config;

import com.markettec.users.adapters.out.jwt.InternalJwtService;
import com.markettec.users.adapters.out.password.Argon2idPasswordHasher;
import com.markettec.users.adapters.out.sql.SqlPasswordAttemptThrottle;
import com.markettec.users.adapters.out.sql.SqlPasswordCredentialRepository;
import com.markettec.users.adapters.out.sql.SqlPasswordSetupGrantRepository;
import com.markettec.users.adapters.out.sql.SqlUserStore;
import com.markettec.users.application.ports.out.PasswordHasher;
import com.markettec.users.application.session.PasswordCredentialService;
import com.markettec.users.application.session.SessionAuthority;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Institutional password credentials. Off unless {@code users.password.enabled=true}; it needs the SQL profile
 * and the internal JWT issuer like every other login method, and adds no new service or datastore.
 */
@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.password", name = "enabled", havingValue = "true")
public class PasswordAuthConfig {
    @Bean
    SqlPasswordCredentialRepository sqlPasswordCredentialRepository(DataSource source) {
        return new SqlPasswordCredentialRepository(new JdbcTemplate(source));
    }

    @Bean
    SqlPasswordSetupGrantRepository sqlPasswordSetupGrantRepository(DataSource source) {
        return new SqlPasswordSetupGrantRepository(new JdbcTemplate(source));
    }

    @Bean
    SqlPasswordAttemptThrottle sqlPasswordAttemptThrottle(DataSource source) {
        return new SqlPasswordAttemptThrottle(new JdbcTemplate(source));
    }

    @Bean
    PasswordHasher passwordHasher(@Value("${users.password.hash.max-concurrent:4}") int maxConcurrent,
                                  @Value("${users.password.hash.wait-millis:2000}") long waitMillis) {
        return new Argon2idPasswordHasher(maxConcurrent, waitMillis);
    }

    @Bean
    PasswordCredentialService passwordCredentialService(SqlUserStore store, SqlPasswordCredentialRepository credentials,
            SqlPasswordSetupGrantRepository grants, SqlPasswordAttemptThrottle throttle, PasswordHasher hasher,
            SessionAuthority sessions, InternalJwtService jwt, Clock clock) {
        return new PasswordCredentialService(store, store, store, credentials, grants, throttle, hasher,
                sessions, jwt, clock);
    }
}

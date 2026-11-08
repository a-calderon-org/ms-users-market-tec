package com.markettec.users.config;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.jwt.InternalJwtService;
import com.markettec.users.adapters.out.mail.HttpEmailLinkSender;
import com.markettec.users.adapters.out.sql.SqlEmailLoginAttemptRepository;
import com.markettec.users.adapters.out.sql.SqlPendingGoogleRegistrationRepository;
import com.markettec.users.adapters.out.sql.SqlUserStore;
import com.markettec.users.application.session.EmailLoginService;
import com.markettec.users.application.session.InstitutionalLinkSender;
import com.markettec.users.application.session.SessionAuthority;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Magic Link delivery and login. Activated only with {@code users.email.enabled=true}; the provider
 * endpoint, key, sender and link URLs must then all be supplied (startup fails closed otherwise).
 */
@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.email", name = "enabled", havingValue = "true")
public class EmailAuthConfig {
    private static final String SUBJECT = "Tu enlace de acceso a MarketTec";

    @Bean
    SqlEmailLoginAttemptRepository sqlEmailLoginAttemptRepository(DataSource source) {
        return new SqlEmailLoginAttemptRepository(new JdbcTemplate(source));
    }

    @Bean
    @Qualifier("loginLinkSender")
    InstitutionalLinkSender loginLinkSender(
            @Value("${users.email.provider.endpoint:}") String endpoint,
            @Value("${users.email.provider.api-key:}") String apiKey,
            @Value("${users.email.from:}") String from,
            @Value("${users.email.login-link-base-url:}") String linkBase) {
        return new HttpEmailLinkSender(client(), endpoint, apiKey, from, linkBase, SUBJECT,
                "Usa este enlace para iniciar sesión. Vence en 10 minutos y solo funciona una vez:", "login");
    }

    @Bean
    @Qualifier("proofLinkSender")
    InstitutionalLinkSender proofLinkSender(
            @Value("${users.email.provider.endpoint:}") String endpoint,
            @Value("${users.email.provider.api-key:}") String apiKey,
            @Value("${users.email.from:}") String from,
            @Value("${users.email.proof-link-base-url:}") String linkBase) {
        return new HttpEmailLinkSender(client(), endpoint, apiKey, from, linkBase,
                "Confirma tu identidad en MarketTec",
                "Usa este enlace para confirmar la operación. Vence en 5 minutos y solo funciona una vez:", "proof");
    }

    @Bean
    EmailLoginService emailLoginService(SqlEmailLoginAttemptRepository attempts, SqlUserStore store,
            SqlPendingGoogleRegistrationRepository emails, SessionAuthority sessions, InternalJwtService jwt,
            @Qualifier("loginLinkSender") InstitutionalLinkSender sender, Clock clock) {
        return new EmailLoginService(attempts, store, store, store, emails, new UuidUserIdGenerator(),
                sessions, jwt, sender, clock, new SecureRandom());
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
}

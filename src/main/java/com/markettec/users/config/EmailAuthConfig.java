package com.markettec.users.config;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.jwt.InternalJwtService;
import com.markettec.users.adapters.out.mail.DetachedLinkSender;
import com.markettec.users.adapters.out.mail.HttpEmailLinkSender;
import com.markettec.users.adapters.out.sql.SqlEmailLoginAttemptRepository;
import com.markettec.users.adapters.out.sql.SqlPasswordCredentialRepository;
import com.markettec.users.adapters.out.sql.SqlPasswordSetupGrantRepository;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Magic Link delivery and login. Activated only with {@code users.email.enabled=true}; the provider
 * endpoint, key, sender and login link URL must then all be supplied (startup fails closed otherwise).
 * The proof-link sender (auth-method linking, optional for its consumer) exists only when its URL is set.
 */
@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.email", name = "enabled", havingValue = "true")
public class EmailAuthConfig {
    private static final String SUBJECT = "Tu enlace de acceso a MarketTec";
    /** Pending recovery deliveries; requests are already capped per address (3/15 min) and per route (30/min). */
    static final int RECOVERY_QUEUE = 64;

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
    @ConditionalOnExpression("!'${users.email.proof-link-base-url:}'.isBlank()")
    InstitutionalLinkSender proofLinkSender(
            @Value("${users.email.provider.endpoint:}") String endpoint,
            @Value("${users.email.provider.api-key:}") String apiKey,
            @Value("${users.email.from:}") String from,
            @Value("${users.email.proof-link-base-url:}") String linkBase) {
        return new HttpEmailLinkSender(client(), endpoint, apiKey, from, linkBase,
                "Confirma tu identidad en MarketTec",
                "Usa este enlace para confirmar la operación. Vence en 5 minutos y solo funciona una vez:", "proof");
    }

    /** Recovery links point at their own page; without this URL, recovery requests answer 503. */
    @Bean
    @Qualifier("recoveryLinkSender")
    @ConditionalOnExpression("!'${users.email.recovery-link-base-url:}'.isBlank()")
    InstitutionalLinkSender recoveryLinkSender(
            @Value("${users.email.provider.endpoint:}") String endpoint,
            @Value("${users.email.provider.api-key:}") String apiKey,
            @Value("${users.email.from:}") String from,
            @Value("${users.email.recovery-link-base-url:}") String linkBase) {
        // Detached: recovery links go only to existing accounts, so the response must not wait for the provider.
        return new DetachedLinkSender(new HttpEmailLinkSender(client(), endpoint, apiKey, from, linkBase,
                "Recupera el acceso a MarketTec",
                "Usa este enlace para recuperar el acceso. Vence en 10 minutos y solo funciona una vez:", "recovery"),
                "recovery", RECOVERY_QUEUE);
    }

    @Bean
    EmailLoginService emailLoginService(SqlEmailLoginAttemptRepository attempts, SqlUserStore store,
            SqlPendingGoogleRegistrationRepository emails, SessionAuthority sessions, InternalJwtService jwt,
            @Qualifier("loginLinkSender") InstitutionalLinkSender sender, Clock clock,
            ObjectProvider<SqlPasswordCredentialRepository> credentials,
            ObjectProvider<SqlPasswordSetupGrantRepository> grants,
            @Qualifier("recoveryLinkSender") ObjectProvider<InstitutionalLinkSender> recoverySender) {
        // Password features are all-or-nothing: without users.password.enabled the plain Magic Link flow is unchanged.
        var passwordRepo = credentials.getIfAvailable();
        var grantRepo = grants.getIfAvailable();
        if (passwordRepo == null || grantRepo == null)
            return new EmailLoginService(attempts, store, store, store, emails, new UuidUserIdGenerator(),
                    sessions, jwt, sender, clock, new SecureRandom());
        return new EmailLoginService(attempts, store, store, store, emails, new UuidUserIdGenerator(),
                sessions, jwt, sender, clock, new SecureRandom(), passwordRepo, grantRepo,
                recoverySender.getIfAvailable());
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
}

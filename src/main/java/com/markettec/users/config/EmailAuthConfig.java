package com.markettec.users.config;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;
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
 * endpoint, sender and login link URL are required. Resend also requires an API key.
 * The proof-link sender (auth-method linking, optional for its consumer) exists only when its URL is set.
 */
@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.email", name = "enabled", havingValue = "true")
public class EmailAuthConfig {
    private static final String SUBJECT = "Tu enlace de acceso a MarketTec";
    /** Pending recovery deliveries; requests are already capped per address (3/15 min) and per route (30/min). */
    static final int RECOVERY_QUEUE = 64;
    /**
     * A recovery link lives 10 minutes; one that waited longer than this is dropped rather than delivered with
     * little validity left (64 queued x 10 s provider deadline could otherwise exceed the whole lifetime).
     */
    static final Duration RECOVERY_MAX_QUEUE_AGE = Duration.ofMinutes(2);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String endpoint;
    private final String apiKey;
    private final String from;
    private final TokenCredential managedIdentity;

    public EmailAuthConfig(
            @Value("${users.email.provider.type:resend}") String provider,
            @Value("${users.email.provider.endpoint:}") String endpoint,
            @Value("${users.email.provider.api-key:}") String apiKey,
            @Value("${users.email.from:}") String from) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.from = from;
        this.managedIdentity = switch (provider) {
            case "resend" -> null;
            case "acs" -> new ManagedIdentityCredentialBuilder().build();
            default -> throw new IllegalArgumentException("Unknown mail provider");
        };
    }

    @Bean
    SqlEmailLoginAttemptRepository sqlEmailLoginAttemptRepository(DataSource source) {
        return new SqlEmailLoginAttemptRepository(new JdbcTemplate(source));
    }

    @Bean
    @Qualifier("loginLinkSender")
    InstitutionalLinkSender loginLinkSender(
            @Value("${users.email.login-link-base-url:}") String linkBase) {
        return linkSender(linkBase, SUBJECT,
                "Usa este enlace para iniciar sesión. Vence en 10 minutos y solo funciona una vez:", "login");
    }

    @Bean
    @Qualifier("proofLinkSender")
    @ConditionalOnExpression("!'${users.email.proof-link-base-url:}'.isBlank()")
    InstitutionalLinkSender proofLinkSender(
            @Value("${users.email.proof-link-base-url:}") String linkBase) {
        return linkSender(linkBase,
                "Confirma tu identidad en MarketTec",
                "Usa este enlace para confirmar la operación. Vence en 5 minutos y solo funciona una vez:", "proof");
    }

    /** Recovery links point at their own page; without this URL, recovery requests answer 503. */
    @Bean
    @Qualifier("recoveryLinkSender")
    @ConditionalOnExpression("!'${users.email.recovery-link-base-url:}'.isBlank()")
    InstitutionalLinkSender recoveryLinkSender(Clock clock,
            @Value("${users.email.recovery-link-base-url:}") String linkBase) {
        // Detached: recovery links go only to existing accounts, so the response must not wait for the provider.
        return new DetachedLinkSender(linkSender(linkBase,
                "Recupera el acceso a MarketTec",
                "Usa este enlace para recuperar el acceso. Vence en 10 minutos y solo funciona una vez:", "recovery"),
                "recovery", RECOVERY_QUEUE, RECOVERY_MAX_QUEUE_AGE, clock);
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

    private InstitutionalLinkSender linkSender(String linkBase, String subject, String intro, String channel) {
        if (managedIdentity == null)
            return new HttpEmailLinkSender(client, endpoint, apiKey, from, linkBase, subject, intro, channel);
        return HttpEmailLinkSender.forAcs(client, endpoint, managedIdentity, from, linkBase, subject, intro, channel);
    }
}

package com.markettec.users.config;

import com.markettec.users.adapters.out.sql.SqlEmailProofChallengeRepository;
import com.markettec.users.adapters.out.sql.SqlRecentAttemptRepository;
import com.markettec.users.adapters.out.sql.SqlUserStore;
import com.markettec.users.application.ports.out.GoogleIdentityVerifier;
import com.markettec.users.application.session.AuthMethodsService;
import com.markettec.users.application.session.InstitutionalLinkSender;
import com.markettec.users.application.session.RecentAuthenticationAuthority;
import com.markettec.users.application.session.RecentProofService;
import com.markettec.users.application.session.SessionAuthority;
import java.security.SecureRandom;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Bearer API wiring (profiles, session activity, proofs, auth methods). Needs the internal JWT. */
@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.api.http", name = "enabled", havingValue = "true")
public class UsersApiConfig {
    @Bean
    SqlEmailProofChallengeRepository sqlEmailProofChallengeRepository(DataSource source) {
        return new SqlEmailProofChallengeRepository(new JdbcTemplate(source));
    }

    @Bean
    RecentProofService recentProofService(SessionAuthority sessions, RecentAuthenticationAuthority recent,
            SqlRecentAttemptRepository attempts, SqlEmailProofChallengeRepository challenges, SqlUserStore store,
            ObjectProvider<GoogleIdentityVerifier> google,
            @Qualifier("proofLinkSender") ObjectProvider<InstitutionalLinkSender> sender,
            @Value("${users.google.login-audience:}") String audience, Clock clock) {
        return new RecentProofService(sessions, recent, attempts, challenges, store, store,
                google.getIfAvailable(), audience, sender.getIfAvailable(), clock, new SecureRandom());
    }

    @Bean
    AuthMethodsService authMethodsService(SessionAuthority sessions, RecentAuthenticationAuthority recent,
            SqlUserStore store, ObjectProvider<GoogleIdentityVerifier> google,
            @Value("${users.google.login-audience:}") String audience) {
        return new AuthMethodsService(sessions, recent, store, store, google.getIfAvailable(), audience);
    }
}

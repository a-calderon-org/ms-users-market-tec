package com.markettec.users.config;

import com.markettec.users.adapters.out.jwt.InternalJwtService;
import com.markettec.users.adapters.out.sql.SqlGoogleLoginIntentRepository;
import com.markettec.users.adapters.out.sql.SqlPendingGoogleRegistrationRepository;
import com.markettec.users.adapters.out.sql.SqlUserStore;
import com.markettec.users.application.ports.out.GoogleIdentityVerifier;
import com.markettec.users.application.session.GoogleLoginService;
import com.markettec.users.application.session.GoogleRegistrationService;
import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.InstitutionalLinkSender;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.google", name = "login-enabled", havingValue = "true")
public class GoogleLoginConfig {
    @Bean
    GoogleLoginService googleLoginService(SqlGoogleLoginIntentRepository intents, SqlUserStore transactions,
            GoogleIdentityVerifier verifier, SessionAuthority sessions, InternalJwtService jwt,
            @Value("${users.google.login-audience:}") String audience, Clock clock) {
        return new GoogleLoginService(intents, transactions, verifier, sessions, jwt,
                audience, clock, new SecureRandom());
    }

    @Bean
    GoogleRegistrationService googleRegistrationService(SqlGoogleLoginIntentRepository intents,
            SqlPendingGoogleRegistrationRepository pending,
            SqlUserStore store, GoogleIdentityVerifier verifier, SessionAuthority sessions,
            InternalJwtService jwt, @Value("${users.google.login-audience:}") String audience, Clock clock,
            @Value("${users.google.registration.enabled:false}") boolean enabled, Environment environment) {
        if (enabled && !environment.acceptsProfiles(Profiles.of("sql-integration")))
            throw new IllegalStateException("Institutional registration requires reviewed production delivery");
        InstitutionalLinkSender simulated = (recipient, attemptId, secret) -> {
            // Test-only simulation has no external side effect or secret logging.
        };
        return new GoogleRegistrationService(intents, pending, store, verifier, store, store,
                new UuidUserIdGenerator(), sessions, jwt, simulated, audience, clock, new SecureRandom());
    }
}

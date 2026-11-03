package com.markettec.users.config;

import com.markettec.users.adapters.out.jwt.InternalJwtService;
import com.markettec.users.adapters.out.sql.SqlGoogleLoginIntentRepository;
import com.markettec.users.adapters.out.sql.SqlUserStore;
import com.markettec.users.application.ports.out.GoogleIdentityVerifier;
import com.markettec.users.application.session.GoogleLoginService;
import com.markettec.users.application.session.SessionAuthority;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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
}

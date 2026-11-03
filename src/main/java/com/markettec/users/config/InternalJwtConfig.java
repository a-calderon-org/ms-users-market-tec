package com.markettec.users.config;

import com.markettec.users.adapters.out.jwt.InternalJwtKeyRing;
import com.markettec.users.adapters.out.jwt.InternalJwtService;
import com.markettec.users.application.session.SessionAuthority;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Opt-in only after private signing material is supplied from outside the repository. */
@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(name = "users.internal-jwt.enabled", havingValue = "true")
public class InternalJwtConfig {
    @Bean
    InternalJwtKeyRing internalJwtKeyRing(@Value("${users.internal-jwt.keys-json}") String keys,
                                          @Value("${users.internal-jwt.active-kid}") String activeKid) {
        return InternalJwtKeyRing.fromJson(keys, activeKid);
    }

    @Bean
    InternalJwtService internalJwtService(SessionAuthority authority, InternalJwtKeyRing keys,
            @Value("${users.internal-jwt.issuer}") String issuer,
            @Value("${users.internal-jwt.audience}") String audience, Clock clock) {
        return new InternalJwtService(authority, keys, issuer, audience, clock, new SecureRandom());
    }
}

package com.markettec.users.config;

import com.markettec.users.application.session.InstitutionalLinkSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Fail startup for a real SQL profile even when Google login and HTTP are disabled. */
@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
public class GoogleRegistrationSafetyConfig {
    @Bean
    InstitutionalLinkSender institutionalLinkSender(
            @Value("${users.google.registration.enabled:false}") boolean enabled, Environment environment) {
        boolean integration = environment.acceptsProfiles(Profiles.of("sql-integration"));
        boolean real = environment.acceptsProfiles(Profiles.of("sql-dev | sql-appservice"));
        if (enabled && (!integration || real))
            throw new IllegalStateException("Institutional registration requires reviewed production delivery");
        // The only configured sender is test-only and has no external side effect.
        return (recipient, attemptId, secret) -> { };
    }
}

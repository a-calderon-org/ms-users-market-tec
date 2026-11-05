package com.markettec.users.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Registration requires the verified Google login and browser HTTP configuration. */
@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
public class GoogleRegistrationSafetyConfig {
    public GoogleRegistrationSafetyConfig(
            @Value("${users.google.registration.enabled:false}") boolean registrationEnabled,
            @Value("${users.google.login-enabled:false}") boolean loginEnabled,
            @Value("${users.google.http.enabled:false}") boolean httpEnabled) {
        if (registrationEnabled && (!loginEnabled || !httpEnabled))
            throw new IllegalStateException("Google registration requires login and secure HTTP");
    }
}

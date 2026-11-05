package com.markettec.users.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import static org.junit.jupiter.api.Assertions.*;

class GoogleRegistrationSafetyConfigTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(GoogleRegistrationSafetyConfig.class);

    @Test void realProfilesAllowEnabledRegistrationWithGoogleLoginAndHttp() {
        for (String profile : new String[]{"sql-dev", "sql-appservice", "sql-integration"}) {
            contexts.withPropertyValues("spring.profiles.active=" + profile,
                    "users.google.registration.enabled=true",
                    "users.google.http.enabled=true",
                    "users.google.login-enabled=true").run(context ->
                        assertNull(context.getStartupFailure(), profile));
        }
    }

    @Test void registrationRequiresLoginAndHttpAndMalformedFlagFailsStartup() {
        for (String flag : new String[]{"users.google.http.enabled=false",
                "users.google.login-enabled=false"}) {
            contexts.withPropertyValues("spring.profiles.active=sql-appservice",
                    "users.google.registration.enabled=true",
                    "users.google.http.enabled=true", "users.google.login-enabled=true",
                    flag).run(context -> {
                        assertNotNull(context.getStartupFailure());
                        assertTrue(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure())
                                .getMessage().contains("requires login and secure HTTP"));
                    });
        }
        contexts.withPropertyValues("spring.profiles.active=sql-dev",
                "users.google.registration.enabled=truish").run(context ->
                    assertNotNull(context.getStartupFailure()));
    }

    @Test void disabledRegistrationNeedsNoSender() {
        contexts.withPropertyValues("spring.profiles.active=sql-appservice").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(
                    com.markettec.users.application.session.InstitutionalLinkSender.class).isEmpty());
        });
    }
}

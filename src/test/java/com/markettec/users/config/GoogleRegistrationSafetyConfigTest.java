package com.markettec.users.config;

import com.markettec.users.application.session.InstitutionalLinkSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;

import static org.junit.jupiter.api.Assertions.*;

class GoogleRegistrationSafetyConfigTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(GoogleRegistrationSafetyConfig.class);

    @Test void realProfilesRejectEnabledRegistrationWithGoogleHttpAndLoginOff() {
        for (String profile : new String[]{"sql-dev", "sql-appservice", "sql-dev,sql-integration",
                "sql-appservice,sql-integration"}) {
            contexts.withPropertyValues("spring.profiles.active=" + profile,
                    "users.google.registration.enabled=true", "users.google.http.enabled=false",
                    "users.google.login-enabled=false").run(context -> {
                        assertNotNull(context.getStartupFailure(), profile);
                        assertTrue(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure())
                                .getMessage().contains("requires reviewed production delivery"), profile);
                    });
        }
    }

    @Test void absentAndExplicitFalseKeepOnlyInertSender() {
        for (String profile : new String[]{"sql-dev", "sql-appservice", "sql-integration"}) {
            for (String flag : new String[]{null, "false"}) {
                var runner = contexts.withPropertyValues("spring.profiles.active=" + profile,
                        "users.google.http.enabled=true", "users.google.login-enabled=true");
                if (flag != null) runner = runner.withPropertyValues("users.google.registration.enabled=" + flag);
                runner.run(context -> {
                    assertNull(context.getStartupFailure(), profile);
                    assertEquals(1, context.getBeansOfType(InstitutionalLinkSender.class).size());
                    context.getBean(InstitutionalLinkSender.class).send(null, "attempt", "secret");
                });
            }
        }
    }

    @Test void integrationAloneAllowsTestFlowAndMalformedFlagFailsStartup() {
        contexts.withPropertyValues("spring.profiles.active=sql-integration",
                "users.google.registration.enabled=true").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(InstitutionalLinkSender.class).size());
                });
        contexts.withPropertyValues("spring.profiles.active=sql-dev",
                "users.google.registration.enabled=truish").run(context ->
                    assertNotNull(context.getStartupFailure()));
    }
}

package com.markettec.users.config;

import com.markettec.users.adapters.in.web.EmailAuthController;
import com.markettec.users.adapters.in.web.GoogleAuthController;
import com.markettec.users.adapters.in.web.PublicAuthRateLimitFilter;
import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.session.SessionAuthority;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Magic Link registration depends only on users.email.enabled, never on the Google HTTP flag. */
class EmailAuthIndependenceTest {
    private static final String ORIGIN = "https://app.markettec.example";
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withBean(SessionAuthority.class, () -> mock(SessionAuthority.class))
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(EmailAuthController.class, PublicAuthRateLimitConfig.class);

    @Test void emailRoutesAndRateLimitRegisterWithGoogleDisabled() {
        contexts.withPropertyValues("spring.profiles.active=sql-appservice", "users.email.enabled=true",
                "users.google.http.enabled=false", "users.email.allowed-origins=" + ORIGIN).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EmailAuthController.class);
            assertThat(context).hasSingleBean(PublicAuthRateLimitFilter.class);
            assertThat(context).doesNotHaveBean(GoogleAuthController.class);
        });
    }

    @Test void googleHttpAloneNoLongerRegistersEmailRoutes() {
        contexts.withPropertyValues("spring.profiles.active=sql-appservice", "users.google.http.enabled=true",
                "users.google.http.allowed-origins=" + ORIGIN).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EmailAuthController.class);
            assertThat(context).hasSingleBean(PublicAuthRateLimitFilter.class);
        });
    }

    @Test void emailOriginsFallBackToGoogleOriginsAndFailClosedWithoutAny() {
        contexts.withPropertyValues("spring.profiles.active=sql-dev", "users.email.enabled=true",
                "users.google.http.allowed-origins=" + ORIGIN).run(context ->
                    assertThat(context).hasSingleBean(EmailAuthController.class));
        contexts.withPropertyValues("spring.profiles.active=sql-dev", "users.email.enabled=true").run(context ->
                assertThat(context).hasFailed());
    }

    /** Login delivery needs provider settings and the Angular link URL. */
    @Test void emailServiceWiresWithLoginSettingsOnlyAndFailsClosedWithoutThem() {
        var service = new ApplicationContextRunner()
                .withBean(javax.sql.DataSource.class, () -> mock(javax.sql.DataSource.class))
                .withBean(com.markettec.users.adapters.out.sql.SqlUserStore.class,
                        () -> mock(com.markettec.users.adapters.out.sql.SqlUserStore.class))
                .withBean(com.markettec.users.adapters.out.sql.SqlPendingGoogleRegistrationRepository.class,
                        () -> mock(com.markettec.users.adapters.out.sql.SqlPendingGoogleRegistrationRepository.class))
                .withBean(SessionAuthority.class, () -> mock(SessionAuthority.class))
                .withBean(com.markettec.users.adapters.out.jwt.InternalJwtService.class,
                        () -> mock(com.markettec.users.adapters.out.jwt.InternalJwtService.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(EmailAuthConfig.class)
                .withPropertyValues("spring.profiles.active=sql-appservice", "users.email.enabled=true");
        service.withPropertyValues("users.email.provider.endpoint=https://api.mail.example/emails",
                "users.email.provider.api-key=test-key", "users.email.from=MarketTEC <no-reply@mail.example>",
                "users.email.login-link-base-url=" + ORIGIN + "/acceso/correo").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(com.markettec.users.application.session.EmailLoginService.class);
            assertThat(context).getBeans(com.markettec.users.application.session.InstitutionalLinkSender.class)
                    .containsOnlyKeys("loginLinkSender");
        });
        service.withPropertyValues("users.email.provider.endpoint=https://api.mail.example/emails",
                "users.email.from=no-reply@mail.example",
                "users.email.login-link-base-url=" + ORIGIN + "/acceso/correo").run(context ->
                    assertThat(context).hasFailed());
        service.withPropertyValues("users.email.provider.type=acs",
                "users.email.provider.endpoint=https://staging.communication.azure.com",
                "users.email.from=DoNotReply@staging.azurecomm.net",
                "users.email.login-link-base-url=" + ORIGIN + "/acceso/correo").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(com.markettec.users.application.session.EmailLoginService.class);
        });
    }

    @Test void nothingIsRegisteredWithEveryFlagOff() {
        contexts.withPropertyValues("spring.profiles.active=sql-appservice").run(context -> {
            assertThat(context).doesNotHaveBean(EmailAuthController.class);
            assertThat(context).doesNotHaveBean(PublicAuthRateLimitFilter.class);
        });
    }
}

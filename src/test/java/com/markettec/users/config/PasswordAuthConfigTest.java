package com.markettec.users.config;

import com.markettec.users.adapters.in.web.PasswordApiController;
import com.markettec.users.adapters.in.web.PasswordAuthController;
import com.markettec.users.adapters.in.web.PublicAuthRateLimitFilter;
import com.markettec.users.adapters.out.jwt.InternalJwtService;
import com.markettec.users.adapters.out.sql.SqlPendingGoogleRegistrationRepository;
import com.markettec.users.adapters.out.sql.SqlUserStore;
import com.markettec.users.application.ports.in.VerifiedInternalToken;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.ports.out.PasswordHasher;
import com.markettec.users.application.session.EmailLoginService;
import com.markettec.users.application.session.InstitutionalLinkSender;
import com.markettec.users.application.session.PasswordCredentialService;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.ports.out.UserRepository;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Wiring of the password feature: off by default, all-or-nothing, and fail-closed on origins. */
class PasswordAuthConfigTest {
    private static final String ORIGIN = "https://app.markettec.example";

    private ApplicationContextRunner base() {
        return new ApplicationContextRunner()
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(SqlUserStore.class, () -> mock(SqlUserStore.class))
                .withBean(SqlPendingGoogleRegistrationRepository.class, () -> mock(SqlPendingGoogleRegistrationRepository.class))
                .withBean(SessionAuthority.class, () -> mock(SessionAuthority.class))
                .withBean(InternalJwtService.class, () -> mock(InternalJwtService.class))
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(InternalTokenVerifier.class, () -> mock(InternalTokenVerifier.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(PasswordAuthConfig.class, EmailAuthConfig.class)
                .withPropertyValues("spring.profiles.active=sql-appservice");
    }

    private static final String[] MAIL = {"users.email.enabled=true", "users.email.provider.endpoint=https://api.mail.example/emails",
            "users.email.provider.api-key=test-key", "users.email.from=MarketTEC <no-reply@mail.example>",
            "users.email.login-link-base-url=" + ORIGIN + "/acceso/correo"};

    @Test void nothingPasswordRelatedExistsByDefault() {
        base().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PasswordCredentialService.class);
            assertThat(context).doesNotHaveBean(PasswordHasher.class);
        });
    }

    @Test void enabledWiresServiceHasherAndAnArgon2idPolicy() {
        base().withPropertyValues("users.password.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PasswordCredentialService.class);
            assertThat(context.getBean(PasswordHasher.class).hash("correct horse battery"))
                    .startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        });
    }

    @Test void magicLinkIsUnchangedWithoutThePasswordFeature() {
        base().withPropertyValues(MAIL).run(context -> {
            assertThat(context).hasNotFailed();
            var email = context.getBean(EmailLoginService.class);
            assertThat(email.recoveryAvailable()).isFalse();
        });
    }

    @Test void recoveryNeedsBothThePasswordFeatureAndItsOwnLinkUrl() {
        String[] recovery = {"users.email.recovery-link-base-url=" + ORIGIN + "/acceso/recuperar"};
        base().withPropertyValues(MAIL).withPropertyValues(recovery).run(context -> {
            assertThat(context.getBean(EmailLoginService.class).recoveryAvailable()).isFalse();
        });
        base().withPropertyValues(MAIL).withPropertyValues("users.password.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmailLoginService.class).recoveryAvailable()).isFalse();
        });
        base().withPropertyValues(MAIL).withPropertyValues("users.password.enabled=true").withPropertyValues(recovery).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmailLoginService.class).recoveryAvailable()).isTrue();
            assertThat(context).getBeans(InstitutionalLinkSender.class).containsOnlyKeys("loginLinkSender", "recoveryLinkSender");
        });
    }

    @Test void routesRegisterOnlyWithTheirFlagsAndFailClosedWithoutHttpsOrigins() {
        var routes = new ApplicationContextRunner()
                .withBean(SessionAuthority.class, () -> mock(SessionAuthority.class))
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withBean(InternalTokenVerifier.class, () -> mock(InternalTokenVerifier.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(PasswordAuthController.class, PasswordApiController.class, PublicAuthRateLimitConfig.class)
                .withPropertyValues("spring.profiles.active=sql-appservice");
        routes.run(context -> {
            assertThat(context).doesNotHaveBean(PasswordAuthController.class);
            assertThat(context).doesNotHaveBean(PasswordApiController.class);
            assertThat(context).doesNotHaveBean(PublicAuthRateLimitFilter.class);
        });
        routes.withPropertyValues("users.password.enabled=true", "users.password.allowed-origins=" + ORIGIN).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PasswordAuthController.class);
            assertThat(context).hasSingleBean(PublicAuthRateLimitFilter.class);
            assertThat(context).doesNotHaveBean(PasswordApiController.class); // needs users.api.http.enabled
        });
        routes.withPropertyValues("users.password.enabled=true", "users.api.http.enabled=true",
                "users.password.allowed-origins=" + ORIGIN).run(context ->
                    assertThat(context).hasSingleBean(PasswordApiController.class));
        routes.withPropertyValues("users.password.enabled=true").run(context -> assertThat(context).hasFailed());
        routes.withPropertyValues("users.password.enabled=true", "users.password.allowed-origins=http://insecure.example")
                .run(context -> assertThat(context).hasFailed());
        // Origins fall back to the Magic Link / Google ones when not set explicitly.
        routes.withPropertyValues("users.password.enabled=true", "users.email.allowed-origins=" + ORIGIN)
                .run(context -> assertThat(context).hasSingleBean(PasswordAuthController.class));
    }
}

package com.markettec.users.config;

import com.markettec.users.adapters.in.web.PublicAuthRateLimitFilter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Basic rate limit for the anonymous auth routes, whenever Google or Magic Link registers them. */
@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnExpression("${users.google.http.enabled:false} or ${users.email.enabled:false} or ${users.password.enabled:false}")
public class PublicAuthRateLimitConfig {
    @Bean
    PublicAuthRateLimitFilter publicAuthRateLimitFilter(Clock clock) {
        return new PublicAuthRateLimitFilter(clock);
    }
}

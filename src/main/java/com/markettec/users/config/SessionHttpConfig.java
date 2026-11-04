package com.markettec.users.config;

import com.markettec.users.adapters.in.web.SessionBootstrapController;
import com.markettec.users.adapters.in.web.SessionPreflightFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.session.http", name = "enabled", havingValue = "true")
public class SessionHttpConfig {
    @Bean
    SessionPreflightFilter sessionPreflightFilter(SessionBootstrapController controller) {
        return new SessionPreflightFilter(controller);
    }
}

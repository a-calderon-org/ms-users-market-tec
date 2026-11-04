package com.markettec.users.config;

import com.markettec.users.adapters.in.web.SessionLogoutController;
import com.markettec.users.adapters.in.web.SessionLogoutPreflightFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.session.logout", name = "enabled", havingValue = "true")
public class SessionLogoutHttpConfig {
    @Bean
    SessionLogoutPreflightFilter sessionLogoutPreflightFilter(SessionLogoutController controller) {
        return new SessionLogoutPreflightFilter(controller);
    }
}

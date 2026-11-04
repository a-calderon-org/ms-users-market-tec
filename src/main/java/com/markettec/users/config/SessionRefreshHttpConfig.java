package com.markettec.users.config;

import com.markettec.users.adapters.in.web.SessionRefreshController;
import com.markettec.users.adapters.in.web.SessionRefreshPreflightFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.session.refresh", name = "enabled", havingValue = "true")
public class SessionRefreshHttpConfig {
    @Bean
    SessionRefreshPreflightFilter sessionRefreshPreflightFilter(SessionRefreshController controller) {
        return new SessionRefreshPreflightFilter(controller);
    }
}

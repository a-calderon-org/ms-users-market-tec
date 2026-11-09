package com.markettec.users.config;

import com.markettec.users.adapters.in.web.GoogleAuthController;
import com.markettec.users.adapters.in.web.GooglePreflightFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.google.http", name = "enabled", havingValue = "true")
public class GoogleHttpConfig {
    @Bean
    GooglePreflightFilter googlePreflightFilter(GoogleAuthController controller) {
        return new GooglePreflightFilter(controller);
    }
}

package com.markettec.users.config;

import com.markettec.users.application.ports.in.GetHealthQuery;
import com.markettec.users.application.usecases.GetHealthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ApplicationConfig {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    GetHealthQuery getHealthQuery(@Value("${spring.application.name}") String serviceName, Clock clock) {
        return new GetHealthService(serviceName, clock);
    }
}

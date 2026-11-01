package com.markettec.users.config;

import com.markettec.users.adapters.out.sql.SqlReadinessHealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile("sql-dev | sql-appservice | sql-integration")
public class SqlHealthConfig {
    @Bean
    SqlReadinessHealthIndicator sqlReadinessHealthIndicator(DataSource source,
            @Value("${users.sql.readiness.cache-ttl:30s}") Duration cacheTtl) {
        return new SqlReadinessHealthIndicator(source, cacheTtl);
    }
}

package com.markettec.users.config;

import com.markettec.users.adapters.in.web.GatewayUserTokenFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Opt-in (MTEC-12/D04): enable only when Container Apps authentication fronts ms-users and APIM relocates the
 * internal user JWT to {@code X-MTEC-User-Authorization}. Off by default; it enables no route by itself.
 */
@Configuration
@ConditionalOnProperty(prefix = "users.gateway.user-token-relocation", name = "enabled", havingValue = "true")
public class GatewayUserTokenConfig {
    @Bean
    GatewayUserTokenFilter gatewayUserTokenFilter() {
        return new GatewayUserTokenFilter();
    }
}

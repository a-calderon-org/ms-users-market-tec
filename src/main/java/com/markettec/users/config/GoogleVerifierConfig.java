package com.markettec.users.config;

import com.markettec.users.application.ports.out.GoogleIdentityVerifier;
import com.markettec.users.adapters.out.google.CachedGooglePublicKeys;
import com.markettec.users.adapters.out.google.GoogleTokenPolicy;
import com.markettec.users.adapters.out.google.NimbusGoogleIdentityVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Opt-in until a trusted auth-attempt protocol can supply expected audience and nonce. */
@Configuration
public class GoogleVerifierConfig {
    @Bean
    @ConditionalOnProperty(prefix = "users.google", name = "enabled", havingValue = "true")
    GoogleIdentityVerifier googleIdentityVerifier(
            @Value("${users.google.audiences:}") String audiences,
            @Value("${users.google.issuers:https://accounts.google.com,accounts.google.com}") String issuers,
            @Value("${users.google.clock-skew:30s}") Duration skew,
            @Value("${users.google.jwks-uri:https://www.googleapis.com/oauth2/v3/certs}") URI jwksUri,
            @Value("${users.google.jwks-max-ttl:10m}") Duration maxTtl,
            @Value("${users.google.jwks-refresh-interval:10s}") Duration refreshInterval,
            Clock clock) {
        var policy = new GoogleTokenPolicy(split(audiences), split(issuers), skew);
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new NimbusGoogleIdentityVerifier(policy,
                new CachedGooglePublicKeys(jwksUri, http, clock, maxTtl, refreshInterval), clock);
    }

    private static Set<String> split(String value) {
        return Arrays.stream(value.split(",", -1)).map(String::trim).collect(Collectors.toSet());
    }
}

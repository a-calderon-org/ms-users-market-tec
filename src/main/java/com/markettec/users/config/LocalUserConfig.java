package com.markettec.users.config;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.application.ports.in.UserUseCases;
import com.markettec.users.application.ports.out.IdentityLinkAuthorization;
import com.markettec.users.application.ports.out.UserIdGenerator;
import com.markettec.users.application.usecases.UserService;
import com.markettec.users.domain.model.UserDomainException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

@Configuration
@Profile("(local | in-memory) & !sql-dev & !sql-integration")
public class LocalUserConfig {
    @Bean
    InMemoryUserStore inMemoryUserStore() {
        return new InMemoryUserStore();
    }

    @Bean
    UserIdGenerator userIdGenerator() {
        return new UuidUserIdGenerator();
    }

    @Bean
    IdentityLinkAuthorization identityLinkAuthorization() {
        return (principal, target, identity) -> {
            throw new UserDomainException(UserDomainException.Reason.UNAUTHORIZED);
        };
    }

    @Bean
    UserUseCases userUseCases(InMemoryUserStore store, UserIdGenerator ids,
                              IdentityLinkAuthorization authorization, Clock clock) {
        return new UserService(store, store, ids, store, authorization, clock);
    }
}

package com.markettec.users.config;

import com.markettec.users.application.ports.in.*;
import com.markettec.users.application.ports.out.IdentityLinkAuthorization;
import com.markettec.users.domain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
class LocalUserConfigTest {
    @Autowired private MockMvc http;
    @Autowired private UserUseCases users;
    @Autowired private IdentityLinkAuthorization authorization;

    @Test
    void clientHeadersAndVerificationFlagsCannotReachTheTrustedUseCases() throws Exception {
        User user = users.createUser(new Email("local@estudiantec.cr"),
                new ProfileDetails("local", "Local", ""), URI.create("https://example.invalid/avatar.png"));
        http.perform(get("/v1/usuarios/" + user.userId().value())
                        .header("X-User-Id", user.userId().value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"));
        http.perform(post("/v1/auth/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verified\":true,\"authorized\":true,\"subject\":\"fake\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void localWiringRunsUseCasesWithLinkingDeniedByDefault() {
        var email = new Email("ana@estudiantec.cr");
        User user = users.createUser(email, new ProfileDetails("ana", "Ana", ""),
                URI.create("https://example.invalid/avatar.png"));
        assertEquals(user.profile(), users.getOwnProfile(user::userId, user.userId()));
        var identity = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", "subject"), email);
        var failure = assertThrows(UserDomainException.class,
                () -> users.linkExternalIdentity(user::userId, user.userId(), identity));
        assertEquals(UserDomainException.Reason.UNAUTHORIZED, failure.reason());
        assertNotNull(authorization);
    }
}

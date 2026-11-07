package com.markettec.users.adapters.in.web;

import com.markettec.users.application.ports.in.VerifiedInternalToken;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.usecases.ProtectedUserOperations;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.time.*;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProtectedApiCorsFilterTest {
    private static final String ORIGIN = "https://app.markettec.example";
    private static final UserId ME = new UserId("usr_12345678-1234-4123-8123-123456789abc");
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private MockMvc mvc;

    @BeforeEach void setup() {
        var jwt = mock(InternalTokenVerifier.class);
        var ops = mock(ProtectedUserOperations.class);
        when(jwt.verify("good")).thenReturn(new VerifiedInternalToken(ME, "a".repeat(32), "b".repeat(32), NOW, NOW.plusSeconds(200)));
        when(ops.getOwnProfile("good", ME)).thenReturn(new UserProfile(ME, new ProfileDetails("ana", "Ana", ""),
                new Email("ana@tec.ac.cr"), URI.create("about:blank"), NOW, NOW, true));
        mvc = MockMvcBuilders.standaloneSetup(new UserProfileController(jwt, ops, Clock.fixed(NOW, ZoneOffset.UTC)))
                .addFilters(new CorrelationIdFilter(), new ProtectedApiCorsFilter(Set.of(ORIGIN))).build();
    }

    @Test void preflightAllowsOnlyConfiguredOriginMethodAndHeaders() throws Exception {
        mvc.perform(options("/v1/me/profile").header("Origin", ORIGIN).header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "authorization, content-type"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET, PUT"));
        mvc.perform(options("/v1/usuarios/" + ME.value()).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "GET")).andExpect(status().isNoContent());
        mvc.perform(options("/v1/auth/proofs/google").header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization, x-csrf-token, content-type"))
                .andExpect(status().isNoContent()).andExpect(header().string("Access-Control-Allow-Methods", "POST"));
        mvc.perform(options("/v1/me/auth-methods").header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isNoContent());
    }

    @Test void preflightRejectsOtherOriginsMethodsHeadersAndNeverUsesWildcard() throws Exception {
        mvc.perform(options("/v1/me/profile").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")).andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/v1/me/profile").header("Origin", ORIGIN).header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/v1/me/profile").header("Origin", ORIGIN).header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "x-secret-header")).andExpect(status().isForbidden());
        mvc.perform(options("/v1/auth/session/activity").header("Origin", ORIGIN).header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test void realRequestsCarryCredentialedCorsOnlyForAllowedOrigins() throws Exception {
        mvc.perform(get("/v1/me/profile").header("Origin", ORIGIN).header("Authorization", "Bearer good"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Vary", org.hamcrest.Matchers.containsString("Origin")));
        mvc.perform(get("/v1/me/profile").header("Origin", ORIGIN)).andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        mvc.perform(get("/v1/me/profile").header("Origin", "https://evil.example").header("Authorization", "Bearer good"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        // server-to-server calls without Origin are unaffected
        mvc.perform(get("/v1/me/profile").header("Authorization", "Bearer good")).andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test void configurationFailsClosedWithoutExplicitHttpsOrigins() {
        assertThrows(IllegalArgumentException.class, () -> new ProtectedApiCorsFilter(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedApiCorsFilter(Set.of("http://app.example")));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedApiCorsFilter(Set.of("*")));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedApiCorsFilter(Set.of("https://app.example/path")));
    }
}

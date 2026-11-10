package com.markettec.users.adapters.in.web;

import com.markettec.users.application.ports.in.VerifiedInternalToken;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.session.RecentProofService;
import com.markettec.users.application.session.SessionAuthority;
import org.springframework.beans.factory.ObjectProvider;
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
    private MockMvc unfiltered;
    private MockMvc proofsMvc;
    private RecentProofService proofs;

    @BeforeEach void setup() {
        var jwt = mock(InternalTokenVerifier.class);
        var ops = mock(ProtectedUserOperations.class);
        when(jwt.verify("good")).thenReturn(new VerifiedInternalToken(ME, "a".repeat(32), "b".repeat(32), NOW, NOW.plusSeconds(200)));
        when(ops.getOwnProfile("good", ME)).thenReturn(new UserProfile(ME, new ProfileDetails("ana", "Ana", ""),
                new Email("ana@tec.ac.cr"), URI.create("about:blank"), NOW, NOW, true));
        var controller = new UserProfileController(jwt, ops, Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CorrelationIdFilter(), new ProtectedApiCorsFilter(Set.of(ORIGIN))).build();
        unfiltered = MockMvcBuilders.standaloneSetup(controller).build();
        var sessions = mock(SessionAuthority.class);
        proofs = mock(RecentProofService.class);
        @SuppressWarnings("unchecked") ObjectProvider<com.markettec.users.application.session.AuthMethodsService> none = mock(ObjectProvider.class);
        proofsMvc = MockMvcBuilders.standaloneSetup(new AuthApiController(jwt, sessions, proofs, none,
                Clock.fixed(NOW, ZoneOffset.UTC), false))
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
        mvc.perform(options("/v1/me/password").header("Origin", ORIGIN).header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "authorization, x-csrf-token, content-type"))
                .andExpect(status().isNoContent()).andExpect(header().string("Access-Control-Allow-Methods", "GET, POST, PUT"));
        mvc.perform(options("/v1/me/password").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "PUT"))
                .andExpect(status().isForbidden());
    }

    /** Codex reproduction: these were configurable and earned a credentialed preflight. */
    @Test void reservedAndBrowserIpv4OriginsCannotBeConfiguredNorEarnCredentialedPreflight() throws Exception {
        for (String forbidden : java.util.List.of("https://invalid", "https://0x7f000001", "https://0xffffffff",
                "https://2130706433", "https://0177.0.0.1", "https://127.1", "https://0x7f.0.0.1")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new ProtectedApiCorsFilter(Set.of(forbidden)), forbidden);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new ProtectedApiCorsFilter(Set.of(ORIGIN, forbidden)), forbidden);
            // With a legitimate allow-list, the forbidden origin is refused on the real preflight path.
            mvc.perform(options("/v1/me/profile").header("Origin", forbidden)
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "authorization"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
        mvc.perform(options("/v1/me/profile").header("Origin", ORIGIN).header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
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

    @Test void framework_routesEncodedAndMatrixVariantsSoTheFilterMustNotLetThemPass() throws Exception {
        // Reproduction: without the filter Spring serves these spellings from the controller.
        unfiltered.perform(get(URI.create("/v1/me/%70rofile")).header("Authorization", "Bearer good")
                .header("Origin", "https://evil.example")).andExpect(status().isOk());
        unfiltered.perform(get(URI.create("/v1/me/profile;audit=1")).header("Authorization", "Bearer good"))
                .andExpect(status().isOk());
    }

    @Test void encodedAndMatrixSpellingsOfProtectedRoutesAreRejectedForEveryOrigin() throws Exception {
        String[] variants = {"/v1/me/%70rofile", "/v1/me/profile;audit=1", "/v1/me;x=1/profile", "/v1/%6De/profile",
                "/v1/usuarios/" + ME.value() + ";a=b", "/v1/usuarios/%75sr_12345678-1234-4123-8123-123456789abc",
                "/v1/auth/proofs/attempts;audit=1", "/v1/auth/session/%61ctivity", "/v1/me/auth-methods;x"};
        for (String path : variants) {
            for (String origin : new String[]{null, ORIGIN, "https://evil.example"}) {
                for (var method : new org.springframework.http.HttpMethod[]{org.springframework.http.HttpMethod.GET,
                        org.springframework.http.HttpMethod.POST, org.springframework.http.HttpMethod.PUT,
                        org.springframework.http.HttpMethod.OPTIONS}) {
                    var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .request(method, URI.create(path)).header("Authorization", "Bearer good")
                            .header("X-CSRF-Token", "d".repeat(64));
                    if (origin != null) request.header("Origin", origin).header("Access-Control-Request-Method", "GET");
                    mvc.perform(request).andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
                }
            }
        }
    }

    @Test void matrixSpellingNeverReachesTheProofControllerEvenWithBearerAndCsrf() throws Exception {
        proofsMvc.perform(post(URI.create("/v1/auth/proofs/attempts;audit=1")).header("Authorization", "Bearer good")
                .header("X-CSRF-Token", "d".repeat(64)).contentType("application/json")
                .content("{\"method\":\"google\",\"operation\":\"LINK_IDENTITY_CURRENT\",\"target\":\"INSTITUTIONAL_EMAIL\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(proofs);
    }

    @Test void doubleEncodingIsNotDecodedTwiceAndIsNotRouted() throws Exception {
        mvc.perform(get(URI.create("/v1/me/%2570rofile")).header("Authorization", "Bearer good"))
                .andExpect(status().isNotFound());
        mvc.perform(get(URI.create("/v1/me/profile")).header("Authorization", "Bearer good")).andExpect(status().isOk());
    }
}

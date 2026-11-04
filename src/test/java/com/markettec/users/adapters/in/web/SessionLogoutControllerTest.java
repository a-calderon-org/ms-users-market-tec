package com.markettec.users.adapters.in.web;

import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionLogoutControllerTest {
    private static final String ORIGIN = "https://app.markettec.example";
    private static final String REFRESH = "a".repeat(64);
    private static final String CSRF = "b".repeat(64);
    private SessionAuthority authority;
    private MockMvc mvc;

    @BeforeEach void setup() {
        authority = mock(SessionAuthority.class);
        var controller = new SessionLogoutController(authority,
                Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC),
                ORIGIN, "https://api.markettec.example", true);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CorrelationIdFilter(), new SessionLogoutPreflightFilter(controller)).build();
    }

    @Test void currentLogoutClearsCookieWithNoBody() throws Exception {
        mvc.perform(valid()).andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Vary", "Origin"))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("__Host-mtec-refresh="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("Path=/"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"))));
        verify(authority).logout(REFRESH, CSRF);
    }

    @Test void missingCookieIsIdempotentAfterRequestValidation() throws Exception {
        mvc.perform(post("/v1/auth/logout").secure(true).header("Origin", ORIGIN)
                .header("X-MTEC-Client", "web").contentType("application/json")
                .content("{\"scope\":\"current\"}"))
                .andExpect(status().isNoContent()).andExpect(header().exists("Set-Cookie"));
        verifyNoInteractions(authority);
    }

    @Test void invalidShapeOriginClientProofAndCookieNeverReachAuthority() throws Exception {
        mvc.perform(valid().content("{\"scope\":\"all\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(valid().content("{\"scope\":\"current\",\"scope\":\"current\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(valid().content("{\"scope\":\"current\",\"extra\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(valid().content("{")) .andExpect(status().isBadRequest());
        mvc.perform(valid().header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        mvc.perform(valid().secure(false)).andExpect(status().isForbidden());
        mvc.perform(valid().header("X-MTEC-Client", "native")).andExpect(status().isForbidden());
        mvc.perform(valid().header("X-CSRF-Token", "wrong")).andExpect(status().isForbidden());
        mvc.perform(valid().cookie(new Cookie("__Host-mtec-refresh", REFRESH),
                new Cookie("__Host-mtec-refresh", REFRESH))).andExpect(status().isUnauthorized());
        verifyNoInteractions(authority);
    }

    @Test void unknownOrConsumedProofDoesNotConfirmLogout() throws Exception {
        doThrow(new SessionRejectedException()).when(authority).logout(REFRESH, CSRF);
        mvc.perform(valid()).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test void preflightOnlyAllowsExactPostAndHeaders() throws Exception {
        mvc.perform(options("/v1/auth/logout").secure(true).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,X-MTEC-Client,X-CSRF-Token"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Methods", "POST"));
        mvc.perform(options("/v1/auth/logout").secure(true).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/v1/auth/logout").secure(true).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "X-MTEC-Client"))
                .andExpect(status().isForbidden());
    }

    @Test void configurationRequiresVerifiedHttps() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SessionLogoutController(authority, Clock.systemUTC(), ORIGIN,
                        "https://api.markettec.example", false));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder valid() {
        return post("/v1/auth/logout").secure(true).header("Origin", ORIGIN)
                .header("X-MTEC-Client", "web").header("X-CSRF-Token", CSRF)
                .cookie(new Cookie("__Host-mtec-refresh", REFRESH))
                .contentType("application/json").content("{\"scope\":\"current\"}");
    }
}

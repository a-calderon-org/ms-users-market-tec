package com.markettec.users.adapters.in.web;

import com.markettec.users.adapters.out.jwt.InternalJwtService;
import com.markettec.users.application.session.Session;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.application.session.SessionTokens;
import com.markettec.users.domain.model.Email;
import com.markettec.users.domain.model.ProfileDetails;
import com.markettec.users.domain.model.User;
import com.markettec.users.domain.model.UserId;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionRefreshControllerTest {
    private static final String ORIGIN = "https://app.markettec.example";
    private static final String OLD = "a".repeat(64);
    private static final String CSRF = "b".repeat(64);
    private static final String NEW = "c".repeat(64);
    private static final String NEXT_CSRF = "d".repeat(64);
    private static final String ID = "e".repeat(32);
    private static final String JWT = "eyJhbGciOiJSUzI1NiJ9.eyJleHAiOjE3OTM3MDc0NDB9.c2ln";
    private final Instant now = Instant.parse("2026-11-03T12:10:00Z");
    private SessionAuthority authority;
    private InternalJwtService jwt;
    private MockMvc mvc;

    @BeforeEach void setup() {
        authority = mock(SessionAuthority.class);
        jwt = mock(InternalJwtService.class);
        var controller = new SessionRefreshController(authority, jwt, Clock.fixed(now, ZoneOffset.UTC),
                ORIGIN, "https://api.markettec.example", true);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CorrelationIdFilter(), new SessionRefreshPreflightFilter(controller)).build();
    }

    @Test void validRotateReturnsAuthResponseAndOnlyCookieCarriesRefresh() throws Exception {
        var created = now.minusSeconds(600);
        var userId = new UserId("usr_12345678-1234-4123-8123-123456789abc");
        var session = Session.start(ID, userId, created);
        var user = User.create(userId, new Email("ana@estudiantec.cr"),
                new ProfileDetails("ana", "Ana", "López"), URI.create("https://example.com/a"), created);
        when(authority.refreshWithSnapshot(eq(OLD), eq(CSRF), same(jwt))).thenReturn(
                new SessionAuthority.RefreshedSession(new SessionTokens(JWT, NEW, NEXT_CSRF,
                        ID, userId.value(), session.deadline()), session, user, now));
        mvc.perform(valid()).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(JWT))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").value(1793707440L))
                .andExpect(jsonPath("$.session.sessionId").value(ID))
                .andExpect(jsonPath("$.session.renewedAt").value(created.getEpochSecond()))
                .andExpect(jsonPath("$.user.userId").value(userId.value()))
                .andExpect(jsonPath("$.csrfToken").value(NEXT_CSRF))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("__Host-mtec-refresh=" + NEW),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("Path=/"),
                        org.hamcrest.Matchers.containsString("SameSite=Lax"))))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Vary", "Origin"))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test void invalidShapeOriginCookieAndCsrfNeverRotate() throws Exception {
        mvc.perform(valid().content("{\"mode\":\"continue\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(valid().content("{\"mode\":\"rotate\",\"extra\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(valid().content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(valid().header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/auth/refresh").secure(true).header("Origin", ORIGIN)
                .header("X-MTEC-Client", "web").header("X-CSRF-Token", CSRF)
                .contentType("application/json").content("{\"mode\":\"rotate\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(valid().cookie(new Cookie("__Host-mtec-refresh", OLD),
                new Cookie("__Host-mtec-refresh", OLD))).andExpect(status().isUnauthorized());
        mvc.perform(valid().header("X-CSRF-Token", "bad")).andExpect(status().isForbidden());
        verifyNoInteractions(authority);
    }

    @Test void unknownOrConsumedRefreshIssuesNoCredential() throws Exception {
        when(authority.refreshWithSnapshot(anyString(), anyString(), same(jwt)))
                .thenThrow(new SessionRejectedException());
        mvc.perform(valid()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_INVALID"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test void preflightRequiresExactOriginPostAndAllowedHeaders() throws Exception {
        mvc.perform(options("/v1/auth/refresh").secure(true).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,X-MTEC-Client,X-CSRF-Token"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Methods", "POST"));
        mvc.perform(options("/v1/auth/refresh").secure(true).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "X-MTEC-Client"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/v1/auth/refresh").secure(true).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder valid() {
        return post("/v1/auth/refresh").secure(true).header("Origin", ORIGIN)
                .header("X-MTEC-Client", "web").header("X-CSRF-Token", CSRF)
                .cookie(new Cookie("__Host-mtec-refresh", OLD))
                .contentType("application/json").content("{\"mode\":\"rotate\"}");
    }
}

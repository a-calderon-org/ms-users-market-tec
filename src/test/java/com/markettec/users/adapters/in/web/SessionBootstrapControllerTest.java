package com.markettec.users.adapters.in.web;

import com.markettec.users.application.session.Session;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SessionBootstrapControllerTest {
    private static final String ORIGIN = "https://app.markettec.example";
    private static final String REFRESH = "a".repeat(64);
    private static final String ID = "b".repeat(32);
    private static final UserId USER_ID = new UserId("usr_12345678-1234-4123-8123-123456789abc");
    private final Instant now = Instant.parse("2026-11-03T12:10:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private SessionAuthority authority;
    private MockMvc mvc;

    @BeforeEach void setup() {
        authority = mock(SessionAuthority.class);
        var controller = new SessionBootstrapController(authority, clock, ORIGIN,
                "https://api.markettec.example", true);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CorrelationIdFilter(), new SessionPreflightFilter(controller)).build();
    }

    @Test void validCookieReturnsCompleteContractAndNoIssuedCredentials() throws Exception {
        Instant created = now.minusSeconds(600);
        var session = Session.start(ID, USER_ID, created);
        var user = User.create(USER_ID, new Email("ana@estudiantec.cr"),
                new ProfileDetails("ana", "Ana", "López"), URI.create("https://example.com/a"), created);
        when(authority.bootstrap(REFRESH)).thenReturn(
                new SessionAuthority.SessionBootstrap(session, user, "c".repeat(64), now));
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", ORIGIN)
                        .header("X-MTEC-Client", "web").cookie(new Cookie("__Host-mtec-refresh", REFRESH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.sessionId").value(ID))
                .andExpect(jsonPath("$.session.status").value("active"))
                .andExpect(jsonPath("$.session.serverTime").value(now.getEpochSecond()))
                .andExpect(jsonPath("$.session.authenticatedAt").value(created.getEpochSecond()))
                .andExpect(jsonPath("$.session.renewedAt").value(created.getEpochSecond()))
                .andExpect(jsonPath("$.session.lastActivityAt").value(created.getEpochSecond()))
                .andExpect(jsonPath("$.session.windowExpiresAt").value(created.plusSeconds(3600).getEpochSecond()))
                .andExpect(jsonPath("$.session.inactivityExpiresAt").value(created.plusSeconds(1800).getEpochSecond()))
                .andExpect(jsonPath("$.session.absoluteExpiresAt").value(created.plusSeconds(28800).getEpochSecond()))
                .andExpect(jsonPath("$.session.expiresAt").value(created.plusSeconds(1800).getEpochSecond()))
                .andExpect(jsonPath("$.session.warningAt").value(created.plusSeconds(1500).getEpochSecond()))
                .andExpect(jsonPath("$.user.userId").value(USER_ID.value()))
                .andExpect(jsonPath("$.user.email").value("ana@estudiantec.cr"))
                .andExpect(jsonPath("$.user.nombre").value("Ana"))
                .andExpect(jsonPath("$.user.fotoPerfil").value("https://example.com/a"))
                .andExpect(jsonPath("$.csrfToken").value("c".repeat(64)))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Vary", "Origin"))
                .andExpect(header().exists("X-Correlation-ID"));
        verify(authority).bootstrap(REFRESH);
        verifyNoMoreInteractions(authority);
    }

    @Test void invalidOriginClientOrCookieNeverReachesAuthority() throws Exception {
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", "https://evil.example")
                .header("X-MTEC-Client", "web").cookie(new Cookie("__Host-mtec-refresh", REFRESH)))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/v1/auth/session").secure(true).header("X-MTEC-Client", "web")
                .header("Sec-Fetch-Site", "same-origin").cookie(new Cookie("__Host-mtec-refresh", REFRESH)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/auth/session").header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .cookie(new Cookie("__Host-mtec-refresh", REFRESH)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", ORIGIN)
                .cookie(new Cookie("__Host-mtec-refresh", REFRESH))).andExpect(status().isForbidden());
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", ORIGIN)
                .header("X-MTEC-Client", "web")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", ORIGIN)
                .header("X-MTEC-Client", "web").cookie(new Cookie("__Host-mtec-refresh", "bad")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", ORIGIN)
                .header("X-MTEC-Client", "web").cookie(new Cookie("__Host-mtec-refresh", REFRESH),
                        new Cookie("__Host-mtec-refresh", REFRESH))).andExpect(status().isUnauthorized());
        verifyNoInteractions(authority);
    }

    @Test void unknownAndConsumedRefreshHaveSamePrivateError() throws Exception {
        when(authority.bootstrap(REFRESH)).thenThrow(new SessionRejectedException());
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", ORIGIN)
                .header("X-MTEC-Client", "web").cookie(new Cookie("__Host-mtec-refresh", REFRESH)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_INVALID"))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test void preflightAllowsOnlyGetAndAllowedHeaders() throws Exception {
        mvc.perform(options("/v1/auth/session").secure(true).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "X-MTEC-Client,X-Correlation-ID"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET"));
        mvc.perform(options("/v1/auth/session").secure(true).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-MTEC-Client"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/v1/auth/session").secure(true).header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "X-MTEC-Client"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/v1/auth/session").secure(true).header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(authority);
    }

    @Test void configurationFailsClosed() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SessionBootstrapController(authority, clock, ORIGIN, "", true));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SessionBootstrapController(authority, clock, ORIGIN, "http://api.example.com", true));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SessionBootstrapController(authority, clock, ORIGIN, "https://api.example.com", false));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SessionBootstrapController(authority, clock, ORIGIN + ",", "https://api.example.com", true));
    }
}

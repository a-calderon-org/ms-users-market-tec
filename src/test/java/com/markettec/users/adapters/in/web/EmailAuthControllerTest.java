package com.markettec.users.adapters.in.web;

import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.session.*;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.time.*;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EmailAuthControllerTest {
    private static final String ORIGIN = "https://app.markettec.example";
    private static final String ATTEMPT = "a".repeat(32);
    private static final String SECRET = "b".repeat(64);
    private static final String BROWSER = "7".repeat(64);
    private static final String USER = "usr_12345678-1234-4123-8123-123456789abc";
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
    private EmailLoginService login;
    private SessionAuthority sessions;
    private UserRepository users;
    private MockMvc mvc;
    private MockMvc unavailable;

    @SuppressWarnings("unchecked")
    @BeforeEach void setup() {
        login = mock(EmailLoginService.class);
        sessions = mock(SessionAuthority.class);
        users = mock(UserRepository.class);
        ObjectProvider<EmailLoginService> present = mock(ObjectProvider.class);
        when(present.getIfAvailable()).thenReturn(login);
        ObjectProvider<EmailLoginService> absent = mock(ObjectProvider.class);
        mvc = MockMvcBuilders.standaloneSetup(new EmailAuthController(present, sessions, users, clock, Set.of(ORIGIN)))
                .addFilters(new CorrelationIdFilter()).build();
        unavailable = MockMvcBuilders.standaloneSetup(new EmailAuthController(absent, sessions, users, clock, Set.of(ORIGIN))).build();
    }

    private static String csrf() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("mtec-email-attempt-csrf:" + BROWSER).getBytes()));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private MockHttpServletRequestBuilder bound(String path, String body) {
        return json(path, body).cookie(new jakarta.servlet.http.Cookie("__Host-mtec-email-attempt", BROWSER))
                .header("X-CSRF-Token", csrf());
    }

    private MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test void requestAnswersAcceptedWithoutRevealingAnything() throws Exception {
        when(login.request(new Email("ana@tec.ac.cr"))).thenReturn(BROWSER);
        mvc.perform(json("/v1/auth/email/requests", "{\"email\":\"ana@tec.ac.cr\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.csrfToken").value(csrf()))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("__Host-mtec-email-attempt=" + BROWSER),
                        org.hamcrest.Matchers.containsString("HttpOnly"), org.hamcrest.Matchers.containsString("Secure"))))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ana"))));
        verify(login).request(new Email("ana@tec.ac.cr"));
    }

    @Test void nonInstitutionalAndMalformedRequestsAreRejected() throws Exception {
        doThrow(new SessionRejectedException()).when(login).request(new Email("a@gmail.com"));
        mvc.perform(json("/v1/auth/email/requests", "{\"email\":\"a@gmail.com\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INSTITUTIONAL_EMAIL_REQUIRED"));
        for (String bad : new String[]{"{}", "{\"email\":\"nope\"}", "{\"email\":1}", "{\"email\":\"a@tec.ac.cr\",\"x\":1}", "[]"})
            mvc.perform(json("/v1/auth/email/requests", bad)).andExpect(status().isBadRequest());
        verify(login, times(1)).request(any());
    }

    @Test void originAndClientHeaderAreEnforcedBeforeAnyWork() throws Exception {
        mvc.perform(post("/v1/auth/email/requests").header("Origin", "https://evil.example").header("X-MTEC-Client", "web")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@tec.ac.cr\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/v1/auth/email/confirm").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/v1/auth/email/requests").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@tec.ac.cr\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(login);
    }

    @Test void routesFailClosedWhenMailIsNotConfigured() throws Exception {
        unavailable.perform(json("/v1/auth/email/requests", "{\"email\":\"a@tec.ac.cr\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("AUTH_TEMPORARILY_UNAVAILABLE"));
        unavailable.perform(bound("/v1/auth/email/confirm", "{\"attemptId\":\"" + ATTEMPT + "\",\"token\":\"" + SECRET + "\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test void deliveryFailureIsTemporaryAndLeaksNothing() throws Exception {
        doThrow(new EmailDeliveryException()).when(login).request(any());
        mvc.perform(json("/v1/auth/email/requests", "{\"email\":\"a@tec.ac.cr\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("AUTH_TEMPORARILY_UNAVAILABLE"));
    }

    @Test void confirmSetsHttpOnlyRefreshCookieAndMapsConsumedAndExpired() throws Exception {
        var signed = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256),
                new com.nimbusds.jwt.JWTClaimsSet.Builder().expirationTime(java.util.Date.from(clock.instant().plusSeconds(240))).build());
        signed.sign(new com.nimbusds.jose.crypto.MACSigner("k".repeat(32).getBytes()));
        String jwt = signed.serialize();
        when(login.confirm(ATTEMPT, BROWSER, SECRET)).thenReturn(new GoogleLoginService.Result(jwt, "c".repeat(32), USER,
                clock.instant().plusSeconds(1800), "d".repeat(64), "e".repeat(64)));
        var now = clock.instant();
        when(sessions.current("c".repeat(32))).thenReturn(Optional.of(Session.start("c".repeat(32), new UserId(USER), now)));
        var user = User.create(new UserId(USER), new Email("ana@tec.ac.cr"), new ProfileDetails("u", "Ana", ""),
                URI.create("about:blank"), now);
        when(users.findById(new UserId(USER))).thenReturn(Optional.of(user));
        mvc.perform(bound("/v1/auth/email/confirm", "{\"attemptId\":\"" + ATTEMPT + "\",\"token\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value("ana@tec.ac.cr"))
                .andExpect(result -> {
                    var cookies = result.getResponse().getHeaders("Set-Cookie");
                    org.junit.jupiter.api.Assertions.assertTrue(cookies.stream().anyMatch(c ->
                            c.startsWith("__Host-mtec-refresh=") && c.contains("HttpOnly") && c.contains("Secure")));
                    org.junit.jupiter.api.Assertions.assertTrue(cookies.stream().anyMatch(c ->
                            c.startsWith("__Host-mtec-email-attempt=;") || c.contains("Max-Age=0")));
                });
        doThrow(new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_CONSUMED)).when(login).confirm(ATTEMPT, BROWSER, SECRET);
        mvc.perform(bound("/v1/auth/email/confirm", "{\"attemptId\":\"" + ATTEMPT + "\",\"token\":\"" + SECRET + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ATTEMPT_CONSUMED"));
        doThrow(new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_EXPIRED)).when(login).confirm(ATTEMPT, BROWSER, SECRET);
        mvc.perform(bound("/v1/auth/email/confirm", "{\"attemptId\":\"" + ATTEMPT + "\",\"token\":\"" + SECRET + "\"}"))
                .andExpect(status().isGone());
        doThrow(new SessionRejectedException()).when(login).confirm(ATTEMPT, BROWSER, SECRET);
        mvc.perform(bound("/v1/auth/email/confirm", "{\"attemptId\":\"" + ATTEMPT + "\",\"token\":\"" + SECRET + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_PROOF_INVALID"));
    }

    @Test void confirmWithoutTheSameBrowserCookieAndCsrfIsRejectedBeforeTheService() throws Exception {
        String body = "{\"attemptId\":\"" + ATTEMPT + "\",\"token\":\"" + SECRET + "\"}";
        mvc.perform(json("/v1/auth/email/confirm", body)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(json("/v1/auth/email/confirm", body).cookie(new jakarta.servlet.http.Cookie("__Host-mtec-email-attempt", BROWSER)))
                .andExpect(status().isForbidden());
        mvc.perform(json("/v1/auth/email/confirm", body).cookie(new jakarta.servlet.http.Cookie("__Host-mtec-email-attempt", "8".repeat(64)))
                .header("X-CSRF-Token", csrf())).andExpect(status().isForbidden());
        mvc.perform(json("/v1/auth/email/confirm", body.replace("token", "secret")).cookie(
                new jakarta.servlet.http.Cookie("__Host-mtec-email-attempt", BROWSER)).header("X-CSRF-Token", csrf()))
                .andExpect(status().isBadRequest());
        verify(login, never()).confirm(any(), any(), any());
    }

    @Test void continuityRequiredIsAConflictWithoutCredentials() throws Exception {
        doThrow(new SessionRejectedException(SessionRejectedException.Reason.CONTINUITY_REQUIRED))
                .when(login).confirm(ATTEMPT, BROWSER, SECRET);
        mvc.perform(bound("/v1/auth/email/confirm", "{\"attemptId\":\"" + ATTEMPT + "\",\"token\":\"" + SECRET + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTINUITY_REQUIRED"))
                .andExpect(header().doesNotExist("Set-Cookie")).andExpect(jsonPath("$.token").doesNotExist());
        verifyNoInteractions(sessions, users);
    }

    @Test void confirmRejectsMalformedBodiesWithoutCallingTheService() throws Exception {
        for (String bad : new String[]{"{}", "{\"attemptId\":\"x\",\"token\":\"" + SECRET + "\"}",
                "{\"attemptId\":\"" + ATTEMPT + "\",\"token\":\"short\"}",
                "{\"attemptId\":\"" + ATTEMPT + "\",\"token\":\"" + SECRET + "\",\"extra\":1}"})
            mvc.perform(json("/v1/auth/email/confirm", bad)).andExpect(status().isBadRequest());
        verify(login, never()).confirm(any(), any(), any());
    }
}

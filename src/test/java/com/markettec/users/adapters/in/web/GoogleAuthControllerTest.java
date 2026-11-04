package com.markettec.users.adapters.in.web;

import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.session.GoogleLoginService;
import com.markettec.users.application.session.GoogleRegistrationService;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class GoogleAuthControllerTest {
    private static final String ORIGIN = "https://app.markettec.example";
    private static final String ATTEMPT_ID = "a".repeat(32);
    private static final String SECRET = "b".repeat(64);
    private static final String SESSION_ID = "c".repeat(32);
    private static final String USER_ID = "usr_12345678-1234-4123-8123-123456789abc";
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
    private GoogleLoginService login;
    private GoogleRegistrationService registration;
    private SessionAuthority sessions;
    private UserRepository users;
    private MockMvc mvc;

    @BeforeEach void setup() {
        login = mock(GoogleLoginService.class);
        registration = mock(GoogleRegistrationService.class);
        sessions = mock(SessionAuthority.class);
        users = mock(UserRepository.class);
        var controller = new GoogleAuthController(login, registration, sessions,
                users, clock, Set.of(ORIGIN), true);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CorrelationIdFilter(), new GooglePreflightFilter(controller)).build();
    }

    @Test void loginAndRegistrationAttemptsKeepPurposeAndPreSessionProofSeparate() throws Exception {
        when(login.begin()).thenReturn(new GoogleLoginService.Challenge(ATTEMPT_ID, "nonce", SECRET,
                clock.instant().plusSeconds(300)));
        when(registration.begin()).thenReturn(new GoogleLoginService.Challenge(ATTEMPT_ID, "nonce2", SECRET,
                clock.instant().plusSeconds(300)));
        mvc.perform(post("/v1/auth/attempts").header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"google\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.purpose").value("login"))
                .andExpect(jsonPath("$.nonce").value("nonce"))
                .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Secure")));
        mvc.perform(post("/v1/auth/attempts").header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"google\",\"purpose\":\"register\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.purpose").value("register"));
        verify(login).begin(); verify(registration).begin();
    }

    @Test void originAndShapeRejectBeforeCreatingAttempt() throws Exception {
        mvc.perform(post("/v1/auth/attempts").header("Origin", "https://evil.example")
                .header("X-MTEC-Client", "web").contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"google\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ORIGIN_DENIED"));
        mvc.perform(post("/v1/auth/attempts").header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"google\",\"userId\":\"x\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(post("/v1/auth/attempts").header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        verifyNoInteractions(login, registration);
    }

    @Test void continuationRejectsMissingOrWrongPreSessionCsrf() throws Exception {
        mvc.perform(proof("/v1/auth/google").cookie(new Cookie("__Host-mtec-attempt", SECRET)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(proof("/v1/auth/google").cookie(new Cookie("__Host-mtec-attempt", SECRET))
                .header("X-CSRF-Token", "wrong"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(login, registration);
    }

    @Test void loginMapsInternalResultAndKeepsRefreshOutOfBody(CapturedOutput output) throws Exception {
        String csrf = csrf();
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJleHAiOjE3OTM3MDc0NDB9.c2ln";
        when(login.complete(ATTEMPT_ID, SECRET, "google-proof"))
                .thenReturn(new GoogleLoginService.Result(jwt, SESSION_ID, USER_ID,
                        clock.instant().plusSeconds(1800), "d".repeat(64), "e".repeat(64)));
        when(sessions.current(SESSION_ID)).thenReturn(Optional.of(Session.start(SESSION_ID,
                new UserId(USER_ID), clock.instant())));
        when(users.findById(new UserId(USER_ID))).thenReturn(Optional.of(User.create(new UserId(USER_ID),
                new Email("user@example.com"), new ProfileDetails("user", "User", ""),
                URI.create("about:blank"), clock.instant())));
        mvc.perform(proof("/v1/auth/google").cookie(new Cookie("__Host-mtec-attempt", SECRET))
                .header("X-CSRF-Token", csrf))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value(jwt))
                .andExpect(jsonPath("$.csrfToken").value("e".repeat(64)))
                .andExpect(jsonPath("$.session.status").value("active"))
                .andExpect(jsonPath("$.user.userId").value(USER_ID))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("google-proof"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("d".repeat(64)))))
                .andExpect(header().stringValues("Set-Cookie", org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("__Host-mtec-refresh="),
                                org.hamcrest.Matchers.containsString("HttpOnly"),
                                org.hamcrest.Matchers.containsString("Secure"),
                                org.hamcrest.Matchers.containsString("SameSite=Lax")))));
        org.junit.jupiter.api.Assertions.assertFalse(output.getOut().contains("google-proof"));
        org.junit.jupiter.api.Assertions.assertFalse(output.getOut().contains("d".repeat(64)));
        org.junit.jupiter.api.Assertions.assertFalse(new GoogleAuthController.AuthResponse(jwt, "Bearer", 0,
                null, null, "e".repeat(64)).toString().contains(jwt));
    }

    @Test void registrationUsesPendingServiceAndMapsReplay() throws Exception {
        when(registration.prepare(eq(ATTEMPT_ID), eq(SECRET), eq("google-proof"), anyString()))
                .thenThrow(new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_CONSUMED));
        mvc.perform(proof("/v1/auth/google/registrations")
                .cookie(new Cookie("__Host-mtec-attempt", SECRET)).header("X-CSRF-Token", csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ATTEMPT_CONSUMED"));
        verifyNoInteractions(login);
    }

    @Test void legacyRegistrationReturns202WithoutCredentials() throws Exception {
        when(registration.prepare(eq(ATTEMPT_ID), eq(SECRET), eq("google-proof"), anyString()))
                .thenReturn(clock.instant().plusSeconds(300));
        mvc.perform(proof("/v1/auth/google/registrations")
                .cookie(new Cookie("__Host-mtec-attempt", SECRET)).header("X-CSRF-Token", csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("mailbox-verification-required"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        verifyNoInteractions(login, sessions, users);
    }

    @Test void canonicalRegistrationAndEmailRequestReturnOnlyPendingAcknowledgments() throws Exception {
        when(registration.isRegistrationAttempt(ATTEMPT_ID)).thenReturn(true);
        when(registration.prepare(eq(ATTEMPT_ID), eq(SECRET), eq("google-proof"), anyString()))
                .thenReturn(clock.instant().plusSeconds(300));
        mvc.perform(proof("/v1/auth/google").cookie(new Cookie("__Host-mtec-attempt", SECRET))
                .header("X-CSRF-Token", csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("mailbox-verification-required"))
                .andExpect(jsonPath("$.token").doesNotExist());
        mvc.perform(post("/v1/auth/email/requests").header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .cookie(new Cookie("__Host-mtec-attempt", SECRET)).header("X-CSRF-Token", csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptId\":\"" + ATTEMPT_ID + "\",\"email\":\"a@TEC.AC.CR\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.token").doesNotExist());
        verify(registration).requestLink(ATTEMPT_ID, SECRET, "a@tec.ac.cr");
        verifyNoInteractions(login, sessions, users);
    }

    @Test void confirmationPostIsOnlyRegistrationRouteThatCanReturnCredentials() throws Exception {
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJleHAiOjE3OTM3MDc0NDB9.c2ln";
        when(registration.confirm(ATTEMPT_ID, SECRET, "e".repeat(64)))
                .thenReturn(new GoogleLoginService.Result(jwt, SESSION_ID, USER_ID,
                        clock.instant().plusSeconds(1800), "d".repeat(64), "f".repeat(64)));
        when(sessions.current(SESSION_ID)).thenReturn(Optional.of(Session.start(SESSION_ID,
                new UserId(USER_ID), clock.instant())));
        when(users.findById(new UserId(USER_ID))).thenReturn(Optional.of(User.create(new UserId(USER_ID),
                new Email("user@tec.ac.cr"), new ProfileDetails("user", "User", ""),
                URI.create("about:blank"), clock.instant())));
        mvc.perform(post("/v1/auth/email/confirm").header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .cookie(new Cookie("__Host-mtec-attempt", SECRET)).header("X-CSRF-Token", csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptId\":\"" + ATTEMPT_ID + "\",\"token\":\"" + "e".repeat(64) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").value(jwt))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        verifyNoInteractions(login);
    }

    @Test void emailEndpointsRequirePostAndOriginalBrowserProof() throws Exception {
        mvc.perform(get("/v1/auth/email/confirm")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/v1/auth/email/confirm").header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptId\":\"" + ATTEMPT_ID + "\",\"token\":\"" + "e".repeat(64) + "\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(registration);
    }

    @Test void registrationDisabledByDefaultWhileLoginRemainsAvailable() throws Exception {
        var blocked = new GoogleAuthController(login, registration, sessions, users, clock, Set.of(ORIGIN));
        var blockedMvc = MockMvcBuilders.standaloneSetup(blocked).build();
        blockedMvc.perform(post("/v1/auth/attempts").header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"google\",\"purpose\":\"register\"}"))
                .andExpect(status().isServiceUnavailable());
        blockedMvc.perform(proof("/v1/auth/google/registrations")).andExpect(status().isServiceUnavailable());
        blockedMvc.perform(post("/v1/auth/email/requests").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isServiceUnavailable());
        blockedMvc.perform(post("/v1/auth/email/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isServiceUnavailable());
        when(login.complete(ATTEMPT_ID, SECRET, "google-proof"))
                .thenThrow(new SessionRejectedException());
        blockedMvc.perform(proof("/v1/auth/google")
                .cookie(new Cookie("__Host-mtec-attempt", SECRET)).header("X-CSRF-Token", csrf()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(registration, sessions, users);
    }

    @Test void preflightAllowsExactOriginAndHeadersOnly() throws Exception {
        mvc.perform(options("/v1/auth/google").header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,X-CSRF-Token,X-MTEC-Client"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        mvc.perform(options("/v1/auth/google").header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Ocp-Apim-Subscription-Key"))
                .andExpect(status().isForbidden());
    }

    @Test void enabledConstructorRequiresExplicitVerifiedHttpsTopology() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new GoogleAuthController(login, registration, sessions, users, clock,
                        ORIGIN, "", false));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new GoogleAuthController(login, registration, sessions, users, clock,
                        ORIGIN, "http://api.markettec.example", true));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder proof(String path) {
        return post(path).header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptId\":\"" + ATTEMPT_ID + "\",\"credential\":\"google-proof\"}");
    }
    private String csrf() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(("mtec-attempt-csrf:" + SECRET)
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
}

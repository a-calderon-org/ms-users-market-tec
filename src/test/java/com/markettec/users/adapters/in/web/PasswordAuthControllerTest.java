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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PasswordAuthControllerTest {
    private static final String ORIGIN = "https://app.markettec.example";
    private static final String USER = "usr_12345678-1234-4123-8123-123456789abc";
    private static final String BROWSER = "7".repeat(64);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC);
    private PasswordCredentialService passwords;
    private EmailLoginService email;
    private SessionAuthority sessions;
    private UserRepository users;
    private MockMvc mvc;
    private MockMvc unavailable;

    @SuppressWarnings("unchecked")
    @BeforeEach void setup() {
        passwords = mock(PasswordCredentialService.class);
        email = mock(EmailLoginService.class);
        sessions = mock(SessionAuthority.class);
        users = mock(UserRepository.class);
        ObjectProvider<PasswordCredentialService> pw = mock(ObjectProvider.class);
        when(pw.getIfAvailable()).thenReturn(passwords);
        ObjectProvider<EmailLoginService> em = mock(ObjectProvider.class);
        when(em.getIfAvailable()).thenReturn(email);
        when(email.recoveryAvailable()).thenReturn(true);
        ObjectProvider<PasswordCredentialService> noPw = mock(ObjectProvider.class);
        ObjectProvider<EmailLoginService> noEm = mock(ObjectProvider.class);
        var secure = get("/").secure(true);
        mvc = MockMvcBuilders.standaloneSetup(new PasswordAuthController(pw, em, sessions, users, clock, Set.of(ORIGIN)))
                .addFilters(new CorrelationIdFilter()).defaultRequest(secure).build();
        unavailable = MockMvcBuilders.standaloneSetup(new PasswordAuthController(noPw, noEm, sessions, users, clock, Set.of(ORIGIN)))
                .defaultRequest(secure).build();
    }

    private MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void signedInAs(String address) throws Exception {
        var signed = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256),
                new com.nimbusds.jwt.JWTClaimsSet.Builder().expirationTime(java.util.Date.from(clock.instant().plusSeconds(240))).build());
        signed.sign(new com.nimbusds.jose.crypto.MACSigner("k".repeat(32).getBytes()));
        when(passwords.login(new Email(address), "correct horse battery")).thenReturn(new GoogleLoginService.Result(
                signed.serialize(), "c".repeat(32), USER, clock.instant().plusSeconds(1800), "d".repeat(64), "e".repeat(64)));
        var now = clock.instant();
        when(sessions.current("c".repeat(32))).thenReturn(Optional.of(Session.start("c".repeat(32), new UserId(USER), now)));
        when(users.findById(new UserId(USER))).thenReturn(Optional.of(User.create(new UserId(USER), new Email(address),
                new ProfileDetails("u", "Ana", ""), URI.create("about:blank"), now)));
    }

    @Test void loginReturnsTheSameAuthResponseAndRefreshCookieAsOtherMethods() throws Exception {
        signedInAs("ana@estudiantec.cr");
        mvc.perform(json("/v1/auth/password/login", "{\"email\":\"ana@estudiantec.cr\",\"password\":\"correct horse battery\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.userId").value(USER)).andExpect(jsonPath("$.user.email").value("ana@estudiantec.cr"))
                .andExpect(jsonPath("$.csrfToken").value("e".repeat(64)))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                        result.getResponse().getHeaders("Set-Cookie").stream().anyMatch(c ->
                                c.startsWith("__Host-mtec-refresh=") && c.contains("HttpOnly") && c.contains("Secure")
                                        && c.contains("SameSite=Lax"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("correct horse"))));
    }

    @Test void failuresAreUniformAndNeverEchoTheCredentials() throws Exception {
        doThrow(new PasswordOperationException(PasswordOperationException.Code.INVALID_CREDENTIALS))
                .when(passwords).login(any(), any());
        mvc.perform(json("/v1/auth/password/login", "{\"email\":\"nobody@tec.ac.cr\",\"password\":\"hunter2 hunter2\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("nobody"))));
    }

    @Test void throttledLoginIs429WithRetryAfterAndTheCorsHeaders() throws Exception {
        doThrow(new PasswordOperationException(PasswordOperationException.Code.THROTTLED, 600))
                .when(passwords).login(any(), any());
        mvc.perform(json("/v1/auth/password/login", "{\"email\":\"ana@tec.ac.cr\",\"password\":\"whatever whatever\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "600"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
    }

    @Test void malformedLoginBodiesNeverReachTheService() throws Exception {
        for (String bad : new String[]{"{}", "{\"email\":\"ana@tec.ac.cr\"}", "{\"password\":\"x\"}", "{\"email\":\"nope\",\"password\":\"x\"}",
                "{\"email\":1,\"password\":\"x\"}", "{\"email\":\"ana@tec.ac.cr\",\"password\":null}",
                "{\"email\":\"ana@tec.ac.cr\",\"password\":\"x\",\"extra\":1}", "[]"})
            mvc.perform(json("/v1/auth/password/login", bad)).andExpect(status().isBadRequest());
        verifyNoInteractions(passwords);
    }

    @Test void originSecureTransportAndClientMarkerAreEnforcedBeforeAnyWork() throws Exception {
        String body = "{\"email\":\"ana@tec.ac.cr\",\"password\":\"x\"}";
        mvc.perform(post("/v1/auth/password/login").header("Origin", "https://evil.example").header("X-MTEC-Client", "web")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/v1/auth/password/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/v1/auth/password/login").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(json("/v1/auth/password/login", body).secure(false).header("X-Forwarded-Proto", "https"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ORIGIN_DENIED"));
        verifyNoInteractions(passwords, email);
    }

    @Test void getAndHeadNeverReachTheServices() throws Exception {
        for (String path : new String[]{"/v1/auth/password/login", "/v1/auth/password/recovery/requests"}) {
            mvc.perform(get(path + "?email=a@tec.ac.cr&password=x").header("Origin", ORIGIN).header("X-MTEC-Client", "web"))
                    .andExpect(status().isMethodNotAllowed());
        }
        verifyNoInteractions(passwords, email);
    }

    @Test void routesFailClosedWhenTheFeatureIsNotAvailable() throws Exception {
        unavailable.perform(json("/v1/auth/password/login", "{\"email\":\"a@tec.ac.cr\",\"password\":\"x\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("AUTH_TEMPORARILY_UNAVAILABLE"));
        unavailable.perform(json("/v1/auth/password/recovery/requests", "{\"email\":\"a@tec.ac.cr\"}"))
                .andExpect(status().isServiceUnavailable());
        when(email.recoveryAvailable()).thenReturn(false);
        mvc.perform(json("/v1/auth/password/recovery/requests", "{\"email\":\"a@tec.ac.cr\"}"))
                .andExpect(status().isServiceUnavailable());
        verify(email, never()).requestRecovery(any());
    }

    @Test void recoveryRequestIsAcknowledgedIdenticallyAndBindsTheBrowser() throws Exception {
        when(email.requestRecovery(new Email("ana@tec.ac.cr"))).thenReturn(BROWSER);
        mvc.perform(json("/v1/auth/password/recovery/requests", "{\"email\":\"ana@tec.ac.cr\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.csrfToken").isString())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("__Host-mtec-email-attempt=" + BROWSER),
                        org.hamcrest.Matchers.containsString("HttpOnly"), org.hamcrest.Matchers.containsString("Secure"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ana"))));
        doThrow(new SessionRejectedException()).when(email).requestRecovery(new Email("a@gmail.com"));
        mvc.perform(json("/v1/auth/password/recovery/requests", "{\"email\":\"a@gmail.com\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INSTITUTIONAL_EMAIL_REQUIRED"));
    }

    @SuppressWarnings("unchecked")
    @Test void constructionFailsClosedWithoutExplicitHttpsOrigins() {
        ObjectProvider<PasswordCredentialService> pw = mock(ObjectProvider.class);
        ObjectProvider<EmailLoginService> em = mock(ObjectProvider.class);
        for (Set<String> bad : java.util.List.of(Set.<String>of(), Set.of("http://app.markettec.example"), Set.of("*"),
                Set.of("https://app.markettec.example/path"), Set.of("https://*.markettec.example"), Set.of("null")))
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new PasswordAuthController(pw, em, sessions, users, clock, bad), bad.toString());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PasswordAuthController(pw, em, sessions, users, clock, ""));
    }
}

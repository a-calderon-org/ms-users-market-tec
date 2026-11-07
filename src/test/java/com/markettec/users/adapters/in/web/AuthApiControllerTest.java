package com.markettec.users.adapters.in.web;

import com.markettec.users.adapters.out.jwt.InternalJwtService.InvalidInternalJwtException;
import com.markettec.users.application.ports.in.VerifiedInternalToken;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.session.*;
import com.markettec.users.domain.model.*;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthApiControllerTest {
    private static final UserId ME = new UserId("usr_12345678-1234-4123-8123-123456789abc");
    private static final String SID = "a".repeat(32);
    private static final String ATTEMPT = "c".repeat(32);
    private static final String CSRF = "d".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InternalTokenVerifier jwt = mock(InternalTokenVerifier.class);
    private final SessionAuthority sessions = mock(SessionAuthority.class);
    private final RecentProofService proofs = mock(RecentProofService.class);
    private final AuthMethodsService methods = mock(AuthMethodsService.class);
    private MockMvc mvc;
    private MockMvc withoutMethods;

    @SuppressWarnings("unchecked")
    @BeforeEach void setup() {
        when(jwt.verify("good")).thenReturn(new VerifiedInternalToken(ME, SID, "b".repeat(32), NOW, NOW.plusSeconds(200)));
        when(jwt.verify("bad")).thenThrow(new InvalidInternalJwtException());
        ObjectProvider<AuthMethodsService> present = mock(ObjectProvider.class);
        when(present.getIfAvailable()).thenReturn(methods);
        ObjectProvider<AuthMethodsService> absent = mock(ObjectProvider.class);
        mvc = MockMvcBuilders.standaloneSetup(new AuthApiController(jwt, sessions, proofs, present, clock)).build();
        withoutMethods = MockMvcBuilders.standaloneSetup(new AuthApiController(jwt, sessions, proofs, absent, clock)).build();
    }

    private MockHttpServletRequestBuilder post_(String path, String body) {
        return post(path).header("Authorization", "Bearer good").header("X-CSRF-Token", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test void everyMutationNeedsBearerAndSessionCsrf() throws Exception {
        String[][] routes = {{"/v1/auth/session/activity", ""}, {"/v1/auth/proofs/attempts", "{}"},
                {"/v1/auth/proofs/google", "{}"}, {"/v1/auth/proofs/email/requests", "{}"},
                {"/v1/auth/proofs/email/confirm", "{}"}, {"/v1/me/auth-methods", "{}"}};
        for (String[] r : routes) {
            mvc.perform(post(r[0]).contentType(MediaType.APPLICATION_JSON).content(r[1])).andExpect(status().isUnauthorized());
            mvc.perform(post(r[0]).header("Authorization", "Bearer bad").header("X-CSRF-Token", CSRF)
                    .contentType(MediaType.APPLICATION_JSON).content(r[1])).andExpect(status().isUnauthorized());
        }
        doThrow(new SessionRejectedException()).when(sessions).requireCsrf(eq(SID), eq(ME), any());
        for (String[] r : routes)
            mvc.perform(post(r[0]).header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON).content(r[1]))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        verify(sessions, never()).recordAdmissibleActivity(any());
        verifyNoInteractions(proofs, methods);
    }

    @Test void activityUsesTheTokenSessionOnly() throws Exception {
        var session = Session.start(SID, ME, NOW);
        when(sessions.recordAdmissibleActivity(SID)).thenReturn(session);
        mvc.perform(post("/v1/auth/session/activity").header("Authorization", "Bearer good").header("X-CSRF-Token", CSRF))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value(SID))
                .andExpect(jsonPath("$.expiresAt").value(session.deadline().getEpochSecond()));
        verify(sessions).requireCsrf(SID, ME, CSRF);
        doThrow(new SessionRejectedException()).when(sessions).recordAdmissibleActivity(SID);
        mvc.perform(post("/v1/auth/session/activity").header("Authorization", "Bearer good").header("X-CSRF-Token", CSRF))
                .andExpect(status().isUnauthorized());
    }

    @Test void proofAttemptMapsMethodsAndRejectsUnknownOnes() throws Exception {
        var attempt = new RecentAttempt(ATTEMPT, ME, SID, RecentAttempt.Method.INSTITUTIONAL_EMAIL, "LINK_IDENTITY",
                "GOOGLE", "e".repeat(64), NOW, NOW.plusSeconds(300), null, false, 0);
        when(proofs.begin(SID, ME, RecentAttempt.Method.INSTITUTIONAL_EMAIL, "LINK_IDENTITY", "GOOGLE")).thenReturn(attempt);
        mvc.perform(post_("/v1/auth/proofs/attempts",
                "{\"method\":\"institutional_email\",\"operation\":\"LINK_IDENTITY\",\"target\":\"GOOGLE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attemptId").value(ATTEMPT))
                .andExpect(jsonPath("$.nonce").value("e".repeat(64))).andExpect(jsonPath("$.expiresAt").value(NOW.getEpochSecond() + 300));
        mvc.perform(post_("/v1/auth/proofs/attempts", "{\"method\":\"sms\",\"operation\":\"LINK_IDENTITY\",\"target\":\"GOOGLE\"}"))
                .andExpect(status().isBadRequest());
        when(proofs.begin(any(), any(), any(), eq("DELETE"), any())).thenThrow(new SessionRejectedException());
        mvc.perform(post_("/v1/auth/proofs/attempts", "{\"method\":\"google\",\"operation\":\"DELETE\",\"target\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void googleAndEmailProofsDelegateWithTheTokenIdentity() throws Exception {
        when(proofs.googleAvailable()).thenReturn(true);
        when(proofs.emailAvailable()).thenReturn(true);
        mvc.perform(post_("/v1/auth/proofs/google", "{\"attemptId\":\"" + ATTEMPT + "\",\"credential\":\"tok\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true));
        verify(proofs).proveGoogle(SID, ME, ATTEMPT, "tok");
        mvc.perform(post_("/v1/auth/proofs/email/requests", "{\"attemptId\":\"" + ATTEMPT + "\"}")).andExpect(status().isAccepted());
        verify(proofs).requestEmail(SID, ME, ATTEMPT);
        String secret = "f".repeat(64);
        mvc.perform(post_("/v1/auth/proofs/email/confirm", "{\"attemptId\":\"" + ATTEMPT + "\",\"secret\":\"" + secret + "\"}"))
                .andExpect(status().isOk());
        verify(proofs).confirmEmail(SID, ME, ATTEMPT, secret);
        mvc.perform(post_("/v1/auth/proofs/email/confirm", "{\"attemptId\":\"" + ATTEMPT + "\",\"secret\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post_("/v1/auth/proofs/google", "{\"attemptId\":\"bad\",\"credential\":\"tok\"}")).andExpect(status().isBadRequest());
    }

    @Test void unconfiguredProvidersFailClosed() throws Exception {
        mvc.perform(post_("/v1/auth/proofs/google", "{\"attemptId\":\"" + ATTEMPT + "\",\"credential\":\"tok\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post_("/v1/auth/proofs/email/requests", "{\"attemptId\":\"" + ATTEMPT + "\"}"))
                .andExpect(status().isServiceUnavailable());
        withoutMethods.perform(get("/v1/me/auth-methods").header("Authorization", "Bearer good"))
                .andExpect(status().isServiceUnavailable());
        verify(proofs, never()).proveGoogle(any(), any(), any(), any());
    }

    @Test void authMethodsListOwnIdentitiesWithoutSubjects() throws Exception {
        var email = new Email("ana@tec.ac.cr");
        when(methods.list(SID, ME)).thenReturn(java.util.List.of(new ExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", "secret-subject"), ME, email)));
        mvc.perform(get("/v1/me/auth-methods").header("Authorization", "Bearer good"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.methods[0].provider").value("google"))
                .andExpect(jsonPath("$.methods[0].status").value("active"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-subject"))));
        mvc.perform(get("/v1/me/auth-methods")).andExpect(status().isUnauthorized());
    }

    @Test void linkingValidatesShapePerProviderAndMapsConflicts() throws Exception {
        var email = new Email("ana@tec.ac.cr");
        var linked = new ExternalIdentity(IdentityProvider.GOOGLE, new ExternalIdentityKey("i", "s"), ME, email);
        when(methods.link(SID, ME, IdentityProvider.GOOGLE, ATTEMPT, "tok")).thenReturn(linked);
        mvc.perform(post_("/v1/me/auth-methods", "{\"provider\":\"google\",\"attemptId\":\"" + ATTEMPT + "\",\"credential\":\"tok\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.provider").value("google"));
        for (String bad : new String[]{"{\"provider\":\"google\",\"attemptId\":\"" + ATTEMPT + "\"}",
                "{\"provider\":\"institutional_email\",\"attemptId\":\"" + ATTEMPT + "\",\"credential\":\"tok\"}",
                "{\"provider\":\"facebook\",\"attemptId\":\"" + ATTEMPT + "\"}", "{\"provider\":\"google\"}",
                "{\"provider\":\"google\",\"attemptId\":\"bad\",\"credential\":\"t\"}"})
            mvc.perform(post_("/v1/me/auth-methods", bad)).andExpect(status().isBadRequest());
        when(methods.link(SID, ME, IdentityProvider.INSTITUTIONAL_EMAIL, ATTEMPT, null))
                .thenThrow(new UserDomainException(UserDomainException.Reason.IDENTITY_CONFLICT));
        mvc.perform(post_("/v1/me/auth-methods", "{\"provider\":\"institutional_email\",\"attemptId\":\"" + ATTEMPT + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDENTITY_CONFLICT"));
    }
}

package com.markettec.users.adapters.in.web;

import com.markettec.users.adapters.out.jwt.InternalJwtService.InvalidInternalJwtException;
import com.markettec.users.application.ports.in.VerifiedInternalToken;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.session.*;
import com.markettec.users.domain.model.UserId;
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

class PasswordApiControllerTest {
    private static final UserId ME = new UserId("usr_12345678-1234-4123-8123-123456789abc");
    private static final String SID = "a".repeat(32);
    private static final String CSRF = "d".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InternalTokenVerifier jwt = mock(InternalTokenVerifier.class);
    private final SessionAuthority sessions = mock(SessionAuthority.class);
    private final PasswordCredentialService passwords = mock(PasswordCredentialService.class);
    private MockMvc mvc;
    private MockMvc unavailable;

    @SuppressWarnings("unchecked")
    @BeforeEach void setup() {
        when(jwt.verify("good")).thenReturn(new VerifiedInternalToken(ME, SID, "b".repeat(32), NOW, NOW.plusSeconds(200)));
        when(jwt.verify("bad")).thenThrow(new InvalidInternalJwtException());
        ObjectProvider<PasswordCredentialService> present = mock(ObjectProvider.class);
        when(present.getIfAvailable()).thenReturn(passwords);
        ObjectProvider<PasswordCredentialService> absent = mock(ObjectProvider.class);
        mvc = MockMvcBuilders.standaloneSetup(new PasswordApiController(jwt, sessions, present, clock)).build();
        unavailable = MockMvcBuilders.standaloneSetup(new PasswordApiController(jwt, sessions, absent, clock)).build();
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b, String body) {
        return b.header("Authorization", "Bearer good").header("X-CSRF-Token", CSRF)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test void everyRouteNeedsABearerAndMutationsAlsoNeedTheSessionCsrf() throws Exception {
        mvc.perform(get("/v1/me/password")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/me/password").header("Authorization", "Bearer bad")).andExpect(status().isUnauthorized());
        for (var verb : new MockHttpServletRequestBuilder[]{post("/v1/me/password"), put("/v1/me/password")}) {
            mvc.perform(verb.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        }
        doThrow(new SessionRejectedException()).when(sessions).requireCsrf(eq(SID), eq(ME), any());
        mvc.perform(post("/v1/me/password").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newPassword\":\"correct horse battery\"}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
        mvc.perform(put("/v1/me/password").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"a\",\"newPassword\":\"b\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(passwords);
    }

    @Test void statusReportsOnlyWhatTheClientNeeds() throws Exception {
        when(passwords.status(SID, ME)).thenReturn(new PasswordCredentialService.Status(false, true));
        mvc.perform(get("/v1/me/password").header("Authorization", "Bearer good"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.setupAvailable").value(true)).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void firstPasswordIsCreatedForTheTokenOwnerOnly() throws Exception {
        mvc.perform(authed(post("/v1/me/password"), "{\"newPassword\":\"correct horse battery\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.configured").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("correct horse"))));
        verify(passwords).setInitial(SID, ME, "correct horse battery");
        // A userId in the body is not a thing: unknown fields are refused.
        mvc.perform(authed(post("/v1/me/password"), "{\"newPassword\":\"correct horse battery\",\"userId\":\"usr_x\"}"))
                .andExpect(status().isBadRequest());
        verify(passwords, times(1)).setInitial(any(), any(), any());
    }

    @Test void changeNeedsTheCurrentPassword() throws Exception {
        mvc.perform(authed(put("/v1/me/password"), "{\"currentPassword\":\"old old old old\",\"newPassword\":\"new new new new 1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.otherSessionsRevoked").value(true));
        verify(passwords).change(SID, ME, "old old old old", "new new new new 1");
        mvc.perform(authed(put("/v1/me/password"), "{\"newPassword\":\"new new new new 1\"}")).andExpect(status().isBadRequest());
    }

    @Test void domainFailuresMapToStableCodes() throws Exception {
        record Case(PasswordOperationException.Code code, int status, String name) { }
        for (var c : java.util.List.of(
                new Case(PasswordOperationException.Code.WEAK_PASSWORD, 422, "WEAK_PASSWORD"),
                new Case(PasswordOperationException.Code.PASSWORD_ALREADY_SET, 409, "PASSWORD_ALREADY_SET"),
                new Case(PasswordOperationException.Code.PASSWORD_NOT_SET, 409, "PASSWORD_NOT_SET"),
                new Case(PasswordOperationException.Code.SETUP_NOT_AUTHORIZED, 403, "PASSWORD_SETUP_NOT_AUTHORIZED"),
                new Case(PasswordOperationException.Code.INVALID_CREDENTIALS, 401, "INVALID_CREDENTIALS"),
                new Case(PasswordOperationException.Code.UNAVAILABLE, 503, "AUTH_TEMPORARILY_UNAVAILABLE"))) {
            reset(passwords);
            doThrow(new PasswordOperationException(c.code())).when(passwords).setInitial(any(), any(), any());
            mvc.perform(authed(post("/v1/me/password"), "{\"newPassword\":\"correct horse battery\"}"))
                    .andExpect(status().is(c.status())).andExpect(jsonPath("$.error.code").value(c.name()));
        }
    }

    @Test void failsClosedWhenTheFeatureIsNotConfigured() throws Exception {
        unavailable.perform(get("/v1/me/password").header("Authorization", "Bearer good")).andExpect(status().isServiceUnavailable());
        unavailable.perform(authed(post("/v1/me/password"), "{\"newPassword\":\"x\"}")).andExpect(status().isServiceUnavailable());
    }

    @Test void malformedJsonIsABadRequest() throws Exception {
        mvc.perform(authed(post("/v1/me/password"), "{not json")).andExpect(status().isBadRequest());
    }
}

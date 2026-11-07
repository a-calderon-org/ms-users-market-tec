package com.markettec.users.adapters.in.web;

import com.markettec.users.adapters.out.jwt.InternalJwtService.InvalidInternalJwtException;
import com.markettec.users.application.ports.in.VerifiedInternalToken;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.application.usecases.ProtectedUserOperations;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserProfileControllerTest {
    private static final UserId ME = new UserId("usr_12345678-1234-4123-8123-123456789abc");
    private static final UserId OTHER = new UserId("usr_22345678-1234-4123-8123-123456789abc");
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InternalTokenVerifier jwt = mock(InternalTokenVerifier.class);
    private final ProtectedUserOperations ops = mock(ProtectedUserOperations.class);
    private MockMvc mvc;
    private UserProfile profile;

    @BeforeEach void setup() {
        profile = new UserProfile(ME, new ProfileDetails("ana", "Ana", "Mora"), new Email("ana@tec.ac.cr"),
                URI.create("https://img.example/a.png"), NOW, NOW, true);
        when(jwt.verify("good")).thenReturn(new VerifiedInternalToken(ME, "a".repeat(32), "b".repeat(32), NOW, NOW.plusSeconds(200)));
        when(jwt.verify("bad")).thenThrow(new InvalidInternalJwtException());
        mvc = MockMvcBuilders.standaloneSetup(new UserProfileController(jwt, ops, clock))
                .addFilters(new CorrelationIdFilter()).build();
    }

    @Test void bearerIsMandatoryAndExact() throws Exception {
        mvc.perform(get("/v1/me/profile")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(get("/v1/me/profile").header("Authorization", "Basic abc")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/me/profile").header("Authorization", "Bearer bad")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        mvc.perform(get("/v1/me/profile").param("access_token", "good")).andExpect(status().isUnauthorized());
        verifyNoInteractions(ops);
    }

    @Test void ownProfileComesFromTheVerifiedSubjectAndIgnoresSpoofedHeaders() throws Exception {
        when(ops.getOwnProfile("good", ME)).thenReturn(profile);
        mvc.perform(get("/v1/me/profile").header("Authorization", "Bearer good").header("X-User-Id", OTHER.value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ME.value())).andExpect(jsonPath("$.estado").value("activo"))
                .andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.fechaActualizacion").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist()).andExpect(jsonPath("$.createdAt").value(NOW.getEpochSecond()))
                .andExpect(jsonPath("$.correo").value("ana@tec.ac.cr")).andExpect(jsonPath("$.verificado").value(true))
                .andExpect(jsonPath("$.nombreUsuario").value("ana")).andExpect(header().string("Cache-Control", "no-store"));
        verify(ops).getOwnProfile("good", ME);
    }

    @Test void otherUsersAreForbiddenAndMalformedIdsRejectedBeforeAnyRead() throws Exception {
        mvc.perform(get("/v1/usuarios/" + OTHER.value()).header("Authorization", "Bearer good")).andExpect(status().isForbidden());
        mvc.perform(put("/v1/usuarios/" + OTHER.value()).header("Authorization", "Bearer good")
                .contentType(MediaType.APPLICATION_JSON).content("{\"nombreUsuario\":\"x\",\"nombre\":\"X\",\"apellidos\":\"\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/v1/usuarios/not-an-id").header("Authorization", "Bearer good")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/usuarios/" + ME.value())).andExpect(status().isUnauthorized());
        verifyNoInteractions(ops);
    }

    @Test void ownerReadsByPathId() throws Exception {
        when(ops.getOwnProfile("good", ME)).thenReturn(profile);
        mvc.perform(get("/v1/usuarios/" + ME.value()).header("Authorization", "Bearer good"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.apellidos").value("Mora"));
    }

    @Test void updateReturnsTheContractResponseWithMessageAndUpdateDate() throws Exception {
        var later = NOW.plusSeconds(60);
        when(ops.updateOwnProfile(eq("good"), eq(ME), any())).thenAnswer(call -> new UserProfile(ME,
                call.getArgument(2), profile.correo(), profile.fotoPerfil(), NOW, later, true));
        mvc.perform(put("/v1/me/profile").header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombreUsuario\":\"ana\",\"nombre\":\"Ana Maria\",\"apellidos\":\"Mora\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ME.value()))
                .andExpect(jsonPath("$.nombre").value("Ana Maria")).andExpect(jsonPath("$.estado").value("activo"))
                .andExpect(jsonPath("$.message").value("Perfil actualizado"))
                .andExpect(jsonPath("$.fechaActualizacion").value(later.toString()))
                .andExpect(jsonPath("$.fechaRegistro").value(NOW.toString())).andExpect(jsonPath("$.userId").doesNotExist());
        verify(ops).updateOwnProfile("good", ME, new ProfileDetails("ana", "Ana Maria", "Mora"));
    }

    @Test void updateRequiresExactlyTheThreeEditableFields() throws Exception {
        for (String body : new String[]{"{\"nombre\":\"Ana\"}", "{\"nombreUsuario\":\"a\",\"nombre\":\"A\"}",
                "{\"nombreUsuario\":\"a\",\"nombre\":\"A\",\"apellidos\":\"\",\"correo\":\"x@tec.ac.cr\"}",
                "{\"nombreUsuario\":\"a\",\"nombre\":\"A\",\"verificado\":true}",
                "{\"nombreUsuario\":\"a\",\"nombre\":\"\",\"apellidos\":\"\"}", "{\"nombreUsuario\":1,\"nombre\":\"A\",\"apellidos\":\"\"}",
                "{}", "[]"})
            mvc.perform(put("/v1/me/profile").header("Authorization", "Bearer good")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(put("/v1/me/profile").header("Authorization", "Bearer good")
                .contentType(MediaType.APPLICATION_JSON).content("{bad")).andExpect(status().isBadRequest());
        verify(ops, never()).updateOwnProfile(any(), any(), any());
    }

    @Test void domainAndSessionFailuresMapWithoutSensitiveData() throws Exception {
        when(ops.getOwnProfile("good", ME)).thenThrow(new UserDomainException(UserDomainException.Reason.USER_NOT_FOUND));
        mvc.perform(get("/v1/me/profile").header("Authorization", "Bearer good")).andExpect(status().isNotFound());
        doThrow(new UserDomainException(UserDomainException.Reason.USER_DEACTIVATED)).when(ops).getOwnProfile("good", ME);
        mvc.perform(get("/v1/me/profile").header("Authorization", "Bearer good")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("USER_DEACTIVATED"));
        doThrow(new SessionRejectedException()).when(ops).getOwnProfile("good", ME);
        mvc.perform(get("/v1/me/profile").header("Authorization", "Bearer good")).andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(ME.value()))));
    }
}

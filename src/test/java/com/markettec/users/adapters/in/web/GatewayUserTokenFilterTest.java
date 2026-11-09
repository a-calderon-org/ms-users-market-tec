package com.markettec.users.adapters.in.web;

import com.markettec.users.application.ports.in.VerifiedInternalToken;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.usecases.ProtectedUserOperations;
import com.markettec.users.config.GatewayUserTokenConfig;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MTEC-12/D04: gateway managed-identity token in Authorization, internal user JWT relocated by APIM. */
class GatewayUserTokenFilterTest {
    private static final UserId ME = new UserId("usr_12345678-1234-4123-8123-123456789abc");
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final String GATEWAY = "Bearer eyJ.gateway-managed-identity.token";
    private final InternalTokenVerifier jwt = mock(InternalTokenVerifier.class);
    private final ProtectedUserOperations ops = mock(ProtectedUserOperations.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        when(jwt.verify("good")).thenReturn(new VerifiedInternalToken(ME, "a".repeat(32), "b".repeat(32), NOW, NOW.plusSeconds(200)));
        when(ops.getOwnProfile("good", ME)).thenReturn(new UserProfile(ME, new ProfileDetails("ana", "Ana", ""),
                new Email("ana@tec.ac.cr"), URI.create("about:blank"), NOW, NOW, true));
        mvc = MockMvcBuilders.standaloneSetup(new UserProfileController(jwt, ops, Clock.fixed(NOW, ZoneOffset.UTC)))
                .addFilters(new CorrelationIdFilter(), new GatewayUserTokenFilter()).build();
    }

    @Test void relocatedUserJwtReachesTheControllerAlongsideTheGatewayToken() throws Exception {
        mvc.perform(get("/v1/me/profile").header("Authorization", GATEWAY)
                        .header(GatewayUserTokenFilter.USER_TOKEN_HEADER, "Bearer good"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombreUsuario").value("ana"));
        verify(jwt).verify("good");
    }

    @Test void gatewayTokenIsNeverReadAsAUserCredential() throws Exception {
        mvc.perform(get("/v1/me/profile").header("Authorization", GATEWAY))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        verifyNoInteractions(jwt, ops);
    }

    @Test void relocatedHeaderStillNeedsExactlyOneVerifiedBearer() throws Exception {
        mvc.perform(get("/v1/me/profile").header(GatewayUserTokenFilter.USER_TOKEN_HEADER, "Bearer good", "Bearer good"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/me/profile").header(GatewayUserTokenFilter.USER_TOKEN_HEADER, "Basic good"))
                .andExpect(status().isUnauthorized());
        when(jwt.verify("forged")).thenThrow(new com.markettec.users.adapters.out.jwt.InternalJwtService.InvalidInternalJwtException());
        mvc.perform(get("/v1/me/profile").header(GatewayUserTokenFilter.USER_TOKEN_HEADER, "Bearer forged"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(ops);
    }

    @Test void relocationIsOptInAndOffByDefault() {
        var contexts = new ApplicationContextRunner().withUserConfiguration(GatewayUserTokenConfig.class);
        contexts.run(context -> assertTrue(context.getBeansOfType(GatewayUserTokenFilter.class).isEmpty()));
        contexts.withPropertyValues("users.gateway.user-token-relocation.enabled=true")
                .run(context -> assertEquals(1, context.getBeansOfType(GatewayUserTokenFilter.class).size()));
    }
}

package com.markettec.users.adapters.in.web;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Activation-manifest evidence for the B07 business surface: with the shipped defaults (no SQL
 * profile, every flag false) the 21 ms-users operations other than {@code getUsersHealth} answer the
 * uniform 404 envelope, emit no cookie or CORS grant, and a preflight grants nothing, even when the
 * request carries every browser credential the real flow would use. The complete effective surface
 * (implicit HEAD/OPTIONS, {@code /error}, URL handlers) is pinned structurally by
 * {@link HttpSurfaceInventoryTest}; the loopback management listener by {@code InternalHealthTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DefaultExposureContractTest {
    private static final String ORIGIN = "https://app.markettec.example";

    /** method, path: the B07 matrix rows owned by ms-users, except getUsersHealth. */
    static final List<String[]> GATED_OPERATIONS = List.of(
            new String[]{"POST", "/v1/auth/attempts"},
            new String[]{"POST", "/v1/auth/google"},
            new String[]{"POST", "/v1/auth/google/registrations"},
            new String[]{"GET", "/v1/auth/session"},
            new String[]{"POST", "/v1/auth/refresh"},
            new String[]{"POST", "/v1/auth/logout"},
            new String[]{"GET", "/v1/me/profile"},
            new String[]{"PUT", "/v1/me/profile"},
            new String[]{"GET", "/v1/usuarios/usr_12345678-1234-4123-8123-123456789abc"},
            new String[]{"PUT", "/v1/usuarios/usr_12345678-1234-4123-8123-123456789abc"},
            new String[]{"GET", "/.well-known/jwks.json"},
            new String[]{"GET", "/.well-known/markettec-identity"},
            new String[]{"POST", "/v1/auth/email/requests"},
            new String[]{"POST", "/v1/auth/email/confirm"},
            new String[]{"POST", "/v1/auth/session/activity"},
            new String[]{"POST", "/v1/auth/proofs/attempts"},
            new String[]{"POST", "/v1/auth/proofs/google"},
            new String[]{"POST", "/v1/auth/proofs/email/requests"},
            new String[]{"POST", "/v1/auth/proofs/email/confirm"},
            new String[]{"GET", "/v1/me/auth-methods"},
            new String[]{"POST", "/v1/me/auth-methods"});

    static final List<String> RISK_FLAGS = List.of(
            "users.internal-jwt.enabled", "users.api.http.enabled", "users.auth-methods.linking.enabled",
            "users.email.enabled", "users.session.logout.enabled", "users.session.refresh.enabled",
            "users.session.http.enabled", "users.session.http.https-topology-verified",
            "users.google.enabled", "users.google.login-enabled", "users.google.registration.enabled",
            "users.google.http.enabled", "users.google.http.same-site-verified",
            "users.gateway.user-token-relocation.enabled");

    @Autowired MockMvc mvc;
    @Autowired Environment environment;

    @Test void everyRiskFlagDefaultsToFalse() {
        assertEquals(14, RISK_FLAGS.size());
        for (String flag : RISK_FLAGS)
            assertEquals(Boolean.FALSE, environment.getProperty(flag, Boolean.class), flag);
        assertEquals(0, environment.getActiveProfiles().length, "no SQL profile by default");
    }

    @Test void onlyHealthIsServedAndEveryGatedOperationIsAnInertNotFound() throws Exception {
        assertEquals(21, GATED_OPERATIONS.size());
        mvc.perform(request(HttpMethod.GET, "/health")).andExpect(status().isOk());
        for (String[] op : GATED_OPERATIONS) {
            var result = mvc.perform(request(HttpMethod.valueOf(op[0]), op[1])
                            .header("Origin", ORIGIN).header("X-MTEC-Client", "web")
                            .header("Authorization", "Bearer a.b.c").header("X-CSRF-Token", "c".repeat(64))
                            .cookie(new jakarta.servlet.http.Cookie("__Host-mtec-refresh", "d".repeat(64)),
                                    new jakarta.servlet.http.Cookie("__Host-mtec-attempt", "e".repeat(64)))
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ROUTE_NOT_FOUND"))
                    .andReturn().getResponse();
            String label = op[0] + " " + op[1];
            assertTrue(result.getHeaders("Set-Cookie").isEmpty(), label);
            assertNull(result.getHeader("Access-Control-Allow-Origin"), label);
            assertNull(result.getHeader("Access-Control-Allow-Credentials"), label);
        }
    }

    @Test void preflightGrantsNoOriginForAnyGatedOperation() throws Exception {
        for (String[] op : GATED_OPERATIONS) {
            var result = mvc.perform(options(op[1]).header("Origin", ORIGIN)
                            .header("Access-Control-Request-Method", op[0])
                            .header("Access-Control-Request-Headers", "content-type,x-csrf-token,x-mtec-client"))
                    .andReturn().getResponse();
            String label = "OPTIONS " + op[1];
            assertNull(result.getHeader("Access-Control-Allow-Origin"), label);
            assertNull(result.getHeader("Access-Control-Allow-Credentials"), label);
        }
    }
}

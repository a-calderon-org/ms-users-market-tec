package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Structural inventory of the main listener with shipped defaults. Instead of probing a fixed list of
 * routes, it enumerates every HandlerMapping source through {@link HttpSurfaceInventory}, so a new
 * controller, overload, condition, functional route or URL handler that is not in the inventory fails
 * the build; {@code HttpSurfaceInventoryMutationTest} proves it with real mutated contexts. Implicit methods, error handling and the separate
 * loopback management listener are classified explicitly (see MS_USERS_ACTIVATION_MANIFEST.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpSurfaceInventoryTest {
    private static final String WEB = "com.markettec.users.adapters.in.web.";
    private static final String BOOT_ERROR = "org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController";
    private static final String RESOURCE = "org.springframework.web.servlet.resource.ResourceHttpRequestHandler";

    /**
     * Exact effective surface of the shipped defaults, one entry per registration (see
     * {@link HttpSurfaceInventory}). Any added, removed, re-conditioned or duplicated registration fails.
     * One {@code MATCH} entry per mapping fixes its effective matching semantics; changing case sensitivity,
     * trailing-separator handling, matcher type, prefixes, negotiation, CORS source or interceptors fails.
     * The Actuator mapping of the main listener ({@code healthEndpointWebMvcHandlerMapping}) is enumerated
     * and is empty because {@code management.endpoint.health.probes.add-additional-paths=false};
     * {@code routerFunctionMapping} has no router function; Actuator itself lives on the loopback
     * management listener ({@code InternalHealthTest}).
     */
    /** Path-pattern semantics fixed for this application: case-sensitive, no optional trailing separator. */
    private static final String PATTERNS = "pathPatterns=true caseSensitive=true optionalTrailingSeparator=false"
            + " separator=/ decodeSegments=true";
    private static final String MVC_INTERCEPTORS = " cors=none interceptors=["
            + "org.springframework.web.servlet.handler.ConversionServiceExposingInterceptor, "
            + "org.springframework.web.servlet.resource.ResourceUrlProviderExposingInterceptor]";

    static final java.util.List<String> EXPECTED = java.util.List.of(
            "MATCH requestMappingHandlerMapping " + PATTERNS + " trailingSlash=false suffix=false"
                    + " registeredSuffix=false prefixes=[]"
                    + " negotiation=[org.springframework.web.accept.HeaderContentNegotiationStrategy]" + MVC_INTERCEPTORS,
            "MATCH healthEndpointWebMvcHandlerMapping " + PATTERNS + " cors=none interceptors=["
                    + "org.springframework.boot.actuate.endpoint.web.servlet.SkipPathExtensionContentNegotiation]",
            "MATCH routerFunctionMapping " + PATTERNS + MVC_INTERCEPTORS,
            "MATCH resourceHandlerMapping " + PATTERNS + " trailingSlash=false" + MVC_INTERCEPTORS,
            "MATCH beanNameHandlerMapping " + PATTERNS + " trailingSlash=false" + MVC_INTERCEPTORS,
            "MATCH welcomePageHandlerMapping " + PATTERNS + " trailingSlash=false" + MVC_INTERCEPTORS,
            "MATCH welcomePageNotAcceptableHandlerMapping " + PATTERNS + " trailingSlash=false" + MVC_INTERCEPTORS,
            "MVC requestMappingHandlerMapping {GET [/health]} -> org.springframework.http.ResponseEntity<"
                    + WEB + "HealthController$HealthResponse> " + WEB
                    + "HealthController.health(jakarta.servlet.http.HttpServletRequest)",
            "MVC requestMappingHandlerMapping { [/**]} -> org.springframework.http.ResponseEntity<"
                    + WEB + "NotFoundController$ErrorEnvelope> " + WEB
                    + "NotFoundController.notFound(jakarta.servlet.http.HttpServletRequest)",
            "MVC requestMappingHandlerMapping { [/error]} -> public org.springframework.http.ResponseEntity<"
                    + "java.util.Map<java.lang.String, java.lang.Object>> " + BOOT_ERROR
                    + ".error(jakarta.servlet.http.HttpServletRequest)",
            "MVC requestMappingHandlerMapping { [/error], produces [text/html]} -> public "
                    + "org.springframework.web.servlet.ModelAndView " + BOOT_ERROR
                    + ".errorHtml(jakarta.servlet.http.HttpServletRequest,jakarta.servlet.http.HttpServletResponse)",
            "URL resourceHandlerMapping /** -> " + RESOURCE,
            "URL resourceHandlerMapping /webjars/** -> " + RESOURCE);

    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test void everyEffectiveMappingIsInventoried() {
        HttpSurfaceInventory.verify(context, EXPECTED);
    }

    /** Implicit methods Spring MVC derives for /health: HEAD mirrors GET without a body; OPTIONS lists Allow. */
    @Test void implicitMethodsOnHealthAreReadOnlyAndGrantNoCors() throws Exception {
        mvc.perform(head("/health").header("Origin", "https://app.markettec.example"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        var options = mvc.perform(options("/health").header("Origin", "https://app.markettec.example"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andReturn().getResponse();
        assertEquals(Set.of("GET", "HEAD", "OPTIONS"), Set.of(options.getHeader("Allow").split(",\\s*")));
        mvc.perform(post("/health")).andExpect(status().isNotFound());
    }

    /**
     * A direct GET /error has no dispatched error attributes, so Spring answers 500. The body carries
     * only timestamp/status/error: no message, exception, trace or binding errors (Boot defaults
     * "never"), and nothing from the request. It is a misleading status, not a disclosure.
     */
    @Test void directErrorRouteDisclosesNothing() throws Exception {
        var response = mvc.perform(get("/error").header("Authorization", "Bearer secret-marker")
                        .queryParam("token", "query-marker").accept("application/json"))
                .andExpect(status().isInternalServerError()).andReturn().getResponse();
        var body = json.readTree(response.getContentAsString());
        var fields = new TreeSet<String>();
        body.fieldNames().forEachRemaining(fields::add);
        assertEquals(new TreeSet<>(Set.of("timestamp", "status", "error")), fields);
        assertFalse(response.getContentAsString().contains("marker"));
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
    }
}

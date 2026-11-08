package com.markettec.users.adapters.in.web;

import com.markettec.inventoryfixtures.InventoryMutations;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Permanent negative evidence for the HTTP surface inventory. Each nested class boots the real
 * application plus exactly one mutation fixture (outside the component scan, never in production
 * code), proves the mutated route is registered and reachable, and runs the same
 * {@link HttpSurfaceInventory#verify} against the same {@link HttpSurfaceInventoryTest#EXPECTED}
 * that guards the shipped application. The inventory must reject every mutation.
 */
class HttpSurfaceInventoryMutationTest {

    private static AssertionError rejected(ApplicationContext context) {
        return assertThrows(AssertionError.class,
                () -> HttpSurfaceInventory.verify(context, HttpSurfaceInventoryTest.EXPECTED));
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(InventoryMutations.FunctionalRoute.class)
    class FunctionalRouteMutation {
        @Autowired ApplicationContext context;
        @Autowired MockMvc mvc;

        @Test void uninventoriedRouterFunctionIsRejected() throws Exception {
            var failure = rejected(context);
            assertTrue(failure.getMessage().contains("FN routerFunctionMapping")
                    && failure.getMessage().contains("/audit-uninventoried-functional"), failure.getMessage());
        }

        @Test void theMutatedFunctionalRouteIsReallyReachable() throws Exception {
            mvc.perform(get("/audit-uninventoried-functional"))
                    .andExpect(status().isOk()).andExpect(content().string("audit-functional"));
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(com.markettec.inventoryfixtures.overload.HealthController.class)
    class OverloadWithParamsMutation {
        @Autowired ApplicationContext context;
        @Autowired MockMvc mvc;

        @Test void overloadOfHealthWithParamsConditionIsRejected() throws Exception {
            mvc.perform(get("/health").queryParam("auditProbe", "1"))
                    .andExpect(status().isOk()).andExpect(content().string("audit-overload"));
            var failure = rejected(context);
            assertTrue(failure.getMessage().contains("params [auditProbe]")
                    && failure.getMessage().contains("com.markettec.inventoryfixtures.overload.HealthController.health("),
                    failure.getMessage());
            assertTrue(failure.getMessage().contains("MISSING: []"), "original mappings must still be present");
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(InventoryMutations.OrdinaryMapping.class)
    class OrdinaryMappingMutation {
        @Autowired ApplicationContext context;
        @Autowired MockMvc mvc;

        @Test void ordinaryUninventoriedGetMappingIsRejected() throws Exception {
            mvc.perform(get("/audit-uninventoried-annotated"))
                    .andExpect(status().isOk()).andExpect(content().string("audit-annotated"));
            var failure = rejected(context);
            assertTrue(failure.getMessage().contains("/audit-uninventoried-annotated"), failure.getMessage());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(com.markettec.inventoryfixtures.conditions.HealthController.class)
    class VerbPatternAndConditionMutation {
        @Autowired ApplicationContext context;
        @Autowired MockMvc mvc;

        @Test void otherVerbAliasPatternAndHeaderConditionsAreRejected() throws Exception {
            mvc.perform(post("/health").header("X-Audit-Probe", "1").contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(content().string("audit-conditions"));
            var failure = rejected(context);
            assertTrue(failure.getMessage().contains("POST [/audit-alias || /health]")
                    && failure.getMessage().contains("headers [X-Audit-Probe]")
                    && failure.getMessage().contains("consumes [application/json]"), failure.getMessage());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(InventoryMutations.UrlMapping.class)
    class UrlHandlerMappingMutation {
        @Autowired ApplicationContext context;
        @Autowired MockMvc mvc;

        @Test void uninventoriedUrlHandlerMappingIsRejected() throws Exception {
            mvc.perform(get("/audit-uninventoried-url"))
                    .andExpect(status().isOk()).andExpect(content().string("audit-url"));
            var failure = rejected(context);
            assertTrue(failure.getMessage().contains("URL auditUrlHandlerMapping /audit-uninventoried-url"),
                    failure.getMessage());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(InventoryMutations.OpaqueMapping.class)
    class OpaqueHandlerMappingMutation {
        @Autowired ApplicationContext context;
        @Autowired MockMvc mvc;

        @Test void unenumerableHandlerMappingFailsClosed() throws Exception {
            mvc.perform(get("/audit-uninventoried-opaque"))
                    .andExpect(status().isOk()).andExpect(content().string("audit-opaque"));
            var failure = rejected(context);
            assertTrue(failure.getMessage().contains("UNENUMERABLE auditOpaqueHandlerMapping"), failure.getMessage());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(InventoryMutations.OpaqueRequestMappingSubclass.class)
    class OpaqueRequestMappingSubclassMutation {
        @Autowired ApplicationContext context;
        @Autowired MockMvc mvc;

        @Test void requestMappingSubclassServingHiddenRoutesIsUnenumerable() throws Exception {
            mvc.perform(get("/audit-unregistered-mvc"))
                    .andExpect(status().isOk()).andExpect(content().string("audit-unregistered-mvc"));
            mvc.perform(get("/health")).andExpect(status().isOk());
            var failure = rejected(context);
            assertTrue(failure.getMessage().contains("UNENUMERABLE requestMappingHandlerMapping "
                    + "com.markettec.inventoryfixtures.InventoryMutations$OpaqueRequestMappingHandlerMapping"),
                    failure.getMessage());
            // The subclass is not trusted, so its inherited registry is not read either.
            assertFalse(failure.getMessage().contains("UNINVENTORIED: [MVC"), failure.getMessage());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(InventoryMutations.CaseInsensitiveMatching.class)
    class CaseInsensitiveMatchingMutation {
        @Autowired ApplicationContext context;
        @Autowired MockMvc mvc;

        @Test void caseInsensitivePathMatchingIsRejectedByTheMatchingRule() throws Exception {
            mvc.perform(get("/health")).andExpect(status().isOk());
            mvc.perform(get("/HEALTH")).andExpect(status().isOk());
            var failure = rejected(context);
            assertTrue(failure.getMessage().contains("MATCH requestMappingHandlerMapping pathPatterns=true caseSensitive=false"),
                    failure.getMessage());
            assertFalse(failure.getMessage().contains("UNENUMERABLE"), failure.getMessage());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(InventoryMutations.GlobalCorsMapping.class)
    class GlobalCorsMappingMutation {
        @Autowired ApplicationContext context;
        @Autowired MockMvc mvc;

        @Test void globalCorsConfigurationWithoutNewRoutesIsRejected() throws Exception {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/health")
                            .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                            .string("Access-Control-Allow-Origin", "https://evil.example"));
            var failure = rejected(context);
            assertTrue(failure.getMessage().contains("cors=[/**]"), failure.getMessage());
        }
    }
}

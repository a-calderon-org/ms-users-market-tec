package com.markettec.inventoryfixtures;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Mutation fixtures for {@code HttpSurfaceInventoryMutationTest}. This package is outside the
 * application's component scan ({@code com.markettec.users}); each fixture is registered only by the
 * test that imports it. None of them exists in the production code.
 */
public final class InventoryMutations {
    private InventoryMutations() { }

    /** Mutation A (Codex reproduction): an uninventoried functional route. */
    public static class FunctionalRoute {
        @Bean
        RouterFunction<ServerResponse> auditUninventoriedFunctional() {
            return RouterFunctions.route()
                    .GET("/audit-uninventoried-functional", request -> ServerResponse.ok().body("audit-functional"))
                    .build();
        }
    }

    /** Mutation C: an ordinary annotated mapping with a new name. */
    @RestController
    public static class OrdinaryMapping {
        @GetMapping("/audit-uninventoried-annotated")
        ResponseEntity<String> auditUninventoriedAnnotated(HttpServletRequest request) {
            return ResponseEntity.ok("audit-annotated");
        }
    }

    /** Another HandlerMapping type: a URL mapping ordered before the annotated mappings. */
    public static class UrlMapping {
        @Bean
        SimpleUrlHandlerMapping auditUrlHandlerMapping() {
            HttpRequestHandler handler = (request, response) -> response.getWriter().write("audit-url");
            var mapping = new SimpleUrlHandlerMapping(Map.of("/audit-uninventoried-url", handler));
            mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return mapping;
        }
    }

    /** A HandlerMapping whose routes cannot be enumerated: must fail closed unless authorized. */
    public static class OpaqueMapping {
        @Bean
        HandlerMapping auditOpaqueHandlerMapping() {
            HttpRequestHandler handler = (request, response) -> response.getWriter().write("audit-opaque");
            return new OpaqueHandlerMapping(handler);
        }
    }

    static final class OpaqueHandlerMapping implements HandlerMapping, Ordered {
        private final Object handler;
        OpaqueHandlerMapping(Object handler) { this.handler = handler; }
        @Override public HandlerExecutionChain getHandler(HttpServletRequest request) {
            return "/audit-uninventoried-opaque".equals(request.getRequestURI()) ? new HandlerExecutionChain(handler) : null;
        }
        @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    }

    /**
     * Codex reproduction: a RequestMappingHandlerMapping subclass installed through WebMvcRegistrations.
     * It keeps every registered route but answers {@code GET /audit-unregistered-mvc} from
     * {@code getHandlerInternal}, so the route never appears in {@code getHandlerMethods()}.
     */
    public static class OpaqueRequestMappingSubclass {
        @Bean
        WebMvcRegistrations auditOpaqueRegistrations() {
            return new WebMvcRegistrations() {
                @Override public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                    return new OpaqueRequestMappingHandlerMapping();
                }
            };
        }
    }

    static final class OpaqueRequestMappingHandlerMapping extends RequestMappingHandlerMapping {
        private final HandlerMethod hidden;
        OpaqueRequestMappingHandlerMapping() {
            try {
                hidden = new HandlerMethod(new HiddenResponder(), HiddenResponder.class.getMethod("serve"));
            } catch (NoSuchMethodException impossible) { throw new IllegalStateException(impossible); }
        }
        @Override protected HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
            if ("GET".equals(request.getMethod()) && "/audit-unregistered-mvc".equals(request.getRequestURI()))
                return hidden;
            return super.getHandlerInternal(request);
        }
    }

    /** Not a controller: never detected by mapping registration. */
    @ResponseBody
    public static final class HiddenResponder {
        public String serve() { return "audit-unregistered-mvc"; }
    }

    /** Codex reproduction: case-insensitive path matching changes reachable routes without new mappings. */
    public static class CaseInsensitiveMatching {
        @Bean
        WebMvcConfigurer auditCaseInsensitiveMatching() {
            return new WebMvcConfigurer() {
                @Override public void configurePathMatch(PathMatchConfigurer configurer) {
                    var parser = new PathPatternParser();
                    parser.setCaseSensitive(false);
                    configurer.setPatternParser(parser);
                }
            };
        }
    }

    /** Matching-semantics variant: a global credentialed CORS mapping added without any new route. */
    public static class GlobalCorsMapping {
        @Bean
        WebMvcConfigurer auditGlobalCors() {
            return new WebMvcConfigurer() {
                @Override public void addCorsMappings(CorsRegistry registry) {
                    registry.addMapping("/**").allowedOrigins("https://evil.example").allowCredentials(true);
                }
            };
        }
    }
}

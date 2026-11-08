package com.markettec.users.adapters.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exact-origin CORS for the Bearer APIs (profiles, proofs, auth methods). Only configured HTTPS origins
 * pass; a browser request from any other origin is refused. Requests without Origin (server-to-server) pass.
 */
public final class ProtectedApiCorsFilter extends OncePerRequestFilter {
    private static final Pattern USER = Pattern.compile("/v1/usuarios/[^/]+");
    private static final Set<String> ALLOWED_HEADERS = Set.of("authorization", "content-type", "x-csrf-token",
            "x-mtec-client", "x-correlation-id");
    private final Set<String> origins;

    public ProtectedApiCorsFilter(Set<String> origins) {
        if (origins == null || origins.isEmpty() || origins.stream().anyMatch(o -> !validOrigin(o)))
            throw new IllegalArgumentException("Explicit HTTPS API origins are required");
        this.origins = Set.copyOf(origins);
    }

    /** Methods a protected path accepts; empty when the path is not a protected API. */
    static List<String> methods(String path) {
        if (path.equals("/v1/me/profile") || USER.matcher(path).matches()) return List.of("GET", "PUT");
        if (path.equals("/v1/me/auth-methods")) return List.of("GET", "POST");
        return switch (path) {
            case "/v1/auth/session/activity", "/v1/auth/proofs/attempts", "/v1/auth/proofs/google",
                 "/v1/auth/proofs/email/requests", "/v1/auth/proofs/email/confirm" -> List.of("POST");
            default -> List.of();
        };
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        // Spring MVC matches decoded segments with path parameters removed. Resolve exactly that form,
        // and refuse any protected route reached through a different spelling (no second decoding).
        String raw;
        String canonical;
        try {
            RequestPath parsed = RequestPath.parse(request.getRequestURI(), request.getContextPath());
            raw = parsed.pathWithinApplication().value();
            var spelled = new StringBuilder();
            for (PathContainer.Element element : parsed.pathWithinApplication().elements()) {
                spelled.append(element instanceof PathContainer.PathSegment segment
                        ? segment.valueToMatch() : element.value());
            }
            canonical = spelled.toString();
        } catch (RuntimeException malformed) {
            raw = request.getRequestURI();
            canonical = "";
        }
        var allowedMethods = methods(canonical);
        if (!allowedMethods.isEmpty() && !raw.equals(canonical)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            Object correlation = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
            response.getWriter().write("{\"error\":{\"code\":\"INVALID_REQUEST\",\"message\":"
                    + "\"No se pudo completar la operación.\",\"details\":[],\"correlationId\":"
                    + (correlation == null ? "null" : "\"" + correlation.toString().replaceAll("[^A-Za-z0-9._-]", "") + "\"")
                    + ",\"timestamp\":" + (System.currentTimeMillis() / 1000) + "}}");
            return;
        }
        String origin = request.getHeader("Origin");
        if (allowedMethods.isEmpty() || origin == null) {
            chain.doFilter(request, response);
            return;
        }
        response.addHeader("Vary", "Origin");
        response.setHeader("Cache-Control", "no-store");
        if (!origins.contains(origin)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if ("OPTIONS".equals(request.getMethod())) {
            String method = request.getHeader("Access-Control-Request-Method");
            String requested = request.getHeader("Access-Control-Request-Headers");
            boolean headersOk = requested == null || Arrays.stream(requested.split(",")).map(String::trim)
                    .map(h -> h.toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).allMatch(ALLOWED_HEADERS::contains);
            if (method == null || !allowedMethods.contains(method) || !headersOk) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            cors(response, origin);
            response.addHeader("Vary", "Access-Control-Request-Method");
            response.setHeader("Access-Control-Allow-Methods", String.join(", ", allowedMethods));
            response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-CSRF-Token, X-MTEC-Client, X-Correlation-ID");
            response.setHeader("Access-Control-Max-Age", "600");
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        cors(response, origin);
        chain.doFilter(request, response);
    }

    private static void cors(HttpServletResponse response, String origin) {
        response.setHeader("Access-Control-Allow-Origin", origin);
        response.setHeader("Access-Control-Allow-Credentials", "true");
        response.setHeader("Access-Control-Expose-Headers", "X-Correlation-ID");
    }

    private static boolean validOrigin(String value) {
        return BrowserOrigins.isCanonicalHttpsOrigin(value);
    }
}

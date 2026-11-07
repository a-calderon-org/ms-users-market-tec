package com.markettec.users.adapters.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
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
        var allowedMethods = methods(request.getRequestURI());
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
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getPort() == -1
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && !uri.getHost().equals("localhost") && !uri.getHost().endsWith(".invalid");
        } catch (RuntimeException invalid) { return false; }
    }
}

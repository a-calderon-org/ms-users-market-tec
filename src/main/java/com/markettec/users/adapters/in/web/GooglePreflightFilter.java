package com.markettec.users.adapters.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** Preflight has no cookie or bearer and must finish before MVC's automatic CORS handler. */
public final class GooglePreflightFilter extends OncePerRequestFilter {
    private static final Set<String> PATHS = Set.of("/v1/auth/attempts", "/v1/auth/google",
            "/v1/auth/google/registrations", "/v1/auth/email/requests", "/v1/auth/email/confirm");
    private final GoogleAuthController controller;

    public GooglePreflightFilter(GoogleAuthController controller) { this.controller = controller; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!"OPTIONS".equals(request.getMethod()) || !PATHS.contains(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        try {
            var result = controller.preflight(request);
            response.setStatus(result.getStatusCode().value());
            result.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        } catch (GoogleAuthController.AuthHttpException rejected) {
            response.setStatus(rejected.status().value());
            response.setHeader("Cache-Control", "no-store");
        }
    }
}

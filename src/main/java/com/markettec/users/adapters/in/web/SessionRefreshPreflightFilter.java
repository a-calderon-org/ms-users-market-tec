package com.markettec.users.adapters.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SessionRefreshPreflightFilter extends OncePerRequestFilter {
    private final SessionRefreshController controller;
    public SessionRefreshPreflightFilter(SessionRefreshController controller) { this.controller = controller; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!"OPTIONS".equals(request.getMethod()) || !"/v1/auth/refresh".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        try {
            var result = controller.preflight(request);
            response.setStatus(result.getStatusCode().value());
            result.getHeaders().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        } catch (RuntimeException rejected) {
            response.setStatus(403);
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Vary", "Origin");
        }
    }
}

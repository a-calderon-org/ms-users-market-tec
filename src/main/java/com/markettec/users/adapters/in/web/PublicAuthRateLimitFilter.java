package com.markettec.users.adapters.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Basic abuse protection for the anonymous authentication endpoints (APIM Consumption has no
 * {@code rate-limit-by-key}). One fixed one-minute window per route and per server instance: once a route's
 * budget is spent, further POSTs get {@code 429 RATE_LIMITED} with {@code Retry-After} and never reach the
 * controller, the database or Google. Staging runs a single replica, so per-instance is also per-environment.
 */
public final class PublicAuthRateLimitFilter extends OncePerRequestFilter {
    /** Requests per minute and per instance; generous for real users, small for scripted abuse. */
    public static final Map<String, Integer> LIMITS = Map.of(
            "/v1/auth/attempts", 120,
            "/v1/auth/google", 60,
            "/v1/auth/google/registrations", 30,
            "/v1/auth/email/requests", 30,
            "/v1/auth/email/confirm", 60);
    private static final long WINDOW_SECONDS = 60;

    private final Map<String, Window> windows;
    private final Clock clock;

    public PublicAuthRateLimitFilter(Clock clock) { this(LIMITS, clock); }

    PublicAuthRateLimitFilter(Map<String, Integer> limits, Clock clock) {
        this.clock = clock;
        this.windows = Map.copyOf(limits.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, e -> new Window(e.getValue()))));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Window window = "POST".equals(request.getMethod()) ? windows.get(request.getRequestURI()) : null;
        long retryAfter = window == null ? 0 : window.acquire(clock.instant().getEpochSecond());
        if (retryAfter == 0) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Object correlation = request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
        String correlationJson = correlation instanceof String id && id.matches("[A-Za-z0-9._-]{1,128}")
                ? "\"" + id + "\"" : "null";
        response.getWriter().write("{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"Demasiadas solicitudes. "
                + "Intente de nuevo en unos segundos.\",\"details\":[],\"correlationId\":" + correlationJson
                + ",\"timestamp\":" + clock.instant().getEpochSecond() + "}}");
    }

    /** Fixed window: {@code start} second and the count inside it. Lock-free, at most one reset per window. */
    private static final class Window {
        private final int limit;
        private final AtomicLong state = new AtomicLong(); // high 40 bits: window start; low 24 bits: count

        Window(int limit) {
            if (limit < 1 || limit >= (1 << 24)) throw new IllegalArgumentException("Invalid rate limit");
            this.limit = limit;
        }

        /** 0 when admitted, otherwise the seconds until the window resets (at least 1). */
        long acquire(long now) {
            while (true) {
                long current = state.get();
                long start = current >>> 24;
                long count = current & 0xFFFFFF;
                if (now - start >= WINDOW_SECONDS || now < start) {
                    if (state.compareAndSet(current, (now << 24) | 1)) return 0;
                } else if (count >= limit) {
                    return Math.max(1, start + WINDOW_SECONDS - now);
                } else if (state.compareAndSet(current, current + 1)) {
                    return 0;
                }
            }
        }
    }
}

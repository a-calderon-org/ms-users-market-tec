package com.markettec.users.adapters.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String ATTRIBUTE_NAME = CorrelationIdFilter.class.getName() + ".correlationId";
    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String candidate = request.getHeader(HEADER_NAME);
        String correlationId = candidate != null && candidate.matches("[A-Za-z0-9._-]{1,128}")
                ? candidate : UUID.randomUUID().toString();
        var previous = MDC.getCopyOfContextMap();
        long startedAt = System.nanoTime();
        request.setAttribute(ATTRIBUTE_NAME, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        MDC.clear();
        MDC.put("correlationId", correlationId);
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error exception) {
            failed = true;
            throw exception;
        } finally {
            try {
                // Downstream code may change MDC; completion belongs to this request.
                MDC.clear();
                MDC.put("correlationId", correlationId);
                Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                LOGGER.atInfo()
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("route", route == null ? "UNMAPPED" : route.toString())
                        .addKeyValue("status", failed && response.getStatus() < 400 ? 500 : response.getStatus())
                        .addKeyValue("durationMs", (System.nanoTime() - startedAt) / 1_000_000)
                        .log("request_completed");
            } finally {
                MDC.clear();
                if (previous != null) MDC.setContextMap(previous);
            }
        }
    }
}

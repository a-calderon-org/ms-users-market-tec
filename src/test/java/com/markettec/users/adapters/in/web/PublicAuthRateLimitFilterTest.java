package com.markettec.users.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicAuthRateLimitFilterTest {
    private Instant now = Instant.parse("2026-11-09T18:00:00Z");
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    };
    private final AtomicInteger reached = new AtomicInteger();

    private MockHttpServletResponse call(PublicAuthRateLimitFilter filter, String method, String path)
            throws Exception {
        var request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setAttribute(CorrelationIdFilter.ATTRIBUTE_NAME, "corr-1");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                reached.incrementAndGet();
            }
        });
        return response;
    }

    @Test void admitsUpToTheLimitThenAnswers429WithoutReachingTheController() throws Exception {
        var filter = new PublicAuthRateLimitFilter(Map.of("/v1/auth/google", 3), clock);
        for (int i = 0; i < 3; i++) assertThat(call(filter, "POST", "/v1/auth/google").getStatus()).isEqualTo(200);
        now = now.plusSeconds(20);
        var limited = call(filter, "POST", "/v1/auth/google");

        assertThat(reached).hasValue(3);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("40");
        assertThat(limited.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(limited.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"", "\"correlationId\":\"corr-1\"");
    }

    @Test void windowResetsAfterOneMinute() throws Exception {
        var filter = new PublicAuthRateLimitFilter(Map.of("/v1/auth/google", 1), clock);
        assertThat(call(filter, "POST", "/v1/auth/google").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/v1/auth/google").getStatus()).isEqualTo(429);
        now = now.plusSeconds(60);
        assertThat(call(filter, "POST", "/v1/auth/google").getStatus()).isEqualTo(200);
    }

    @Test void routesHaveIndependentBudgetsAndOtherRequestsAreNeverCounted() throws Exception {
        var filter = new PublicAuthRateLimitFilter(Map.of("/v1/auth/google", 1, "/v1/auth/attempts", 1), clock);
        assertThat(call(filter, "POST", "/v1/auth/google").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/v1/auth/attempts").getStatus()).isEqualTo(200);
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "OPTIONS", "/v1/auth/google").getStatus()).isEqualTo(200);
            assertThat(call(filter, "GET", "/v1/auth/session").getStatus()).isEqualTo(200);
            assertThat(call(filter, "POST", "/v1/me/profile").getStatus()).isEqualTo(200);
        }
        assertThat(call(filter, "POST", "/v1/auth/google").getStatus()).isEqualTo(429);
    }

    @Test void defaultLimitsCoverEveryAnonymousAuthRoute() {
        assertThat(PublicAuthRateLimitFilter.LIMITS).containsOnlyKeys("/v1/auth/attempts", "/v1/auth/google",
                "/v1/auth/google/registrations", "/v1/auth/email/requests", "/v1/auth/email/confirm");
    }
}

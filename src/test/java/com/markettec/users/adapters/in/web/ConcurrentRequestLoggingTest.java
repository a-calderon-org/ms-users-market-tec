package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "spring.profiles.active=local",
        "server.tomcat.threads.max=2", "server.tomcat.threads.min-spare=2"})
@Import(ConcurrentRequestLoggingTest.Fixture.class)
@ExtendWith(OutputCaptureExtension.class)
class ConcurrentRequestLoggingTest {
    private static final String SECRET = "concurrent-private-marker";
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper json;
    @Autowired RequestState state;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentHttpRequestsIsolateRestoreAndCleanMdcOnReusedServerThreads(
            boolean enclosingContext, CapturedOutput output) throws Exception {
        state.enclosingContext = enclosingContext;
        var correlations = new HashSet<String>();
        Set<Long> previousThreads = null;
        try (var clients = Executors.newFixedThreadPool(2)) {
            for (int wave = 0; wave < 2; wave++) {
                String successId = wave == 0 ? "concurrent-success-" + enclosingContext : null;
                String failureId = wave == 0 ? "concurrent-failure-" + enclosingContext : "invalid id";
                var success = clients.submit(() -> request("ok", successId));
                var failure = clients.submit(() -> request("fail", failureId));
                checkResponse(success.get(20, TimeUnit.SECONDS), 200, successId, correlations);
                checkResponse(failure.get(20, TimeUnit.SECONDS), 500,
                        wave == 0 ? failureId : null, correlations);

                var threads = new HashSet<Long>();
                for (int i = 0; i < 2; i++) {
                    var snapshot = state.completed.poll(10, TimeUnit.SECONDS);
                    assertNotNull(snapshot, "Outer filter must finish on the server thread");
                    assertTrue(correlations.contains(snapshot.correlation()));
                    assertTrue(snapshot.before() == null || snapshot.before().isEmpty(), "No previous request MDC");
                    if (enclosingContext) {
                        assertEquals(Map.of("correlationId", "outer-" + snapshot.thread(),
                                "outer-private", SECRET), snapshot.after());
                    } else {
                        assertTrue(snapshot.after() == null || snapshot.after().isEmpty(), "Request MDC must be cleared");
                    }
                    threads.add(snapshot.thread());
                }
                assertEquals(2, threads.size(), "Both server threads must overlap at the barrier");
                if (previousThreads != null) assertEquals(previousThreads, threads, "Exercise the same server threads again");
                previousThreads = threads;
            }
        }
        assertEquals(4, correlations.size());
        var events = new ArrayList<JsonNode>();
        for (String line : output.getOut().lines().filter(s -> s.startsWith("{")).toList()) {
            events.add(json.readTree(line));
        }
        for (String correlation : correlations) {
            var completions = events.stream().filter(e -> correlation.equals(e.path("correlationId").asText())
                    && "request_completed".equals(e.path("message").asText())).toList();
            assertEquals(1, completions.size());
            assertEquals("/reqa/concurrent/{outcome}", completions.getFirst().path("route").asText());
            int status = completions.getFirst().path("status").asInt();
            assertTrue(status == 200 || status == 500);
            assertEquals(status == 500 ? 1 : 0, events.stream().filter(e -> correlation.equals(e.path("correlationId").asText())
                    && "request_failed".equals(e.path("message").asText())).count());
        }
        for (String marker : List.of(SECRET, "Authorization", "Bearer", "session=", "invalid id", "mutated-child")) {
            assertFalse(output.getOut().contains(marker), "Sensitive request/MDC/exception data must not reach logs");
        }
        assertTrue(state.completed.isEmpty());
    }

    private ResponseEntity<String> request(String outcome, String candidate) {
        var headers = new HttpHeaders();
        if (candidate != null) headers.set(CorrelationIdFilter.HEADER_NAME, candidate);
        headers.set("Authorization", "Bearer " + SECRET);
        headers.set("Cookie", "session=" + SECRET);
        return http.exchange("/reqa/concurrent/" + outcome + "?token=" + SECRET,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private void checkResponse(ResponseEntity<String> response, int status, String expected,
            Set<String> correlations) throws Exception {
        assertEquals(status, response.getStatusCode().value());
        String correlation = response.getHeaders().getFirst(CorrelationIdFilter.HEADER_NAME);
        assertNotNull(correlation);
        if (expected != null) assertEquals(expected, correlation);
        else assertTrue(correlation.matches("[0-9a-f-]{36}"));
        assertTrue(correlations.add(correlation), "Every request must retain its own correlation");
        var body = json.readTree(response.getBody());
        assertEquals(correlation, status == 200 ? body.path("correlationId").asText()
                : body.path("error").path("correlationId").asText());
        if (status == 500) assertEquals("INTERNAL_ERROR", body.path("error").path("code").asText());
        assertFalse(response.getBody().contains(SECRET));
    }

    record Snapshot(long thread, String correlation, Map<String, String> before, Map<String, String> after) { }

    static final class RequestState {
        final CyclicBarrier overlap = new CyclicBarrier(2);
        final BlockingQueue<Snapshot> completed = new LinkedBlockingQueue<>();
        volatile boolean enclosingContext;
    }

    @RestController
    @org.springframework.boot.test.context.TestComponent
    static class OverlappingController {
        private final RequestState state;
        OverlappingController(RequestState state) { this.state = state; }

        @GetMapping("/reqa/concurrent/{outcome}")
        Map<String, String> request(@PathVariable String outcome, HttpServletRequest request) throws Exception {
            String correlation = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
            assertEquals(Map.of("correlationId", correlation), MDC.getCopyOfContextMap());
            MDC.put("child-private", SECRET);
            state.overlap.await(10, TimeUnit.SECONDS);
            assertEquals(Map.of("correlationId", correlation, "child-private", SECRET), MDC.getCopyOfContextMap());
            if ("fail".equals(outcome)) throw new IllegalStateException(SECRET);
            MDC.put("correlationId", "mutated-child");
            return Map.of("correlationId", correlation);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean RequestState requestState() { return new RequestState(); }
        @Bean OverlappingController overlappingController(RequestState state) { return new OverlappingController(state); }
        @Bean FilterRegistrationBean<Filter> enclosingMdcFilter(RequestState state) {
            Filter outer = (request, response, chain) -> {
                var http = (HttpServletRequest) request;
                if (!http.getRequestURI().startsWith("/reqa/concurrent/")) {
                    chain.doFilter(request, response);
                    return;
                }
                long thread = Thread.currentThread().threadId();
                var before = MDC.getCopyOfContextMap();
                if (state.enclosingContext) {
                    MDC.put("correlationId", "outer-" + thread);
                    MDC.put("outer-private", SECRET);
                }
                try { chain.doFilter(request, response); }
                finally {
                    state.completed.add(new Snapshot(thread,
                            (String) http.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME), before, MDC.getCopyOfContextMap()));
                    MDC.clear();
                }
            };
            var registration = new FilterRegistrationBean<>(outer);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}

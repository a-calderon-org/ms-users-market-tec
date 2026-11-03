package com.markettec.users.adapters.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "spring.profiles.active=local"})
class InternalHealthTest {
    @Autowired TestRestTemplate http;
    @Autowired ConfigurableApplicationContext context;
    @Value("${local.management.port}") int managementPort;

    @Test
    void mainListenerDoesNotPublishActuatorOrAdditionalProbePaths() {
        for (String path : new String[]{"/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness", "/readyz", "/livez"}) {
            assertEquals(404, http.getForEntity(path, String.class).getStatusCode().value());
        }
    }

    @Test
    void managementIsLoopbackOnlyAndDoesNotExposeDetailsOrOtherEndpoints() {
        assertEquals("127.0.0.1", context.getEnvironment().getProperty("management.server.address"));
        var root = http.getForEntity(internal("/actuator/health"), String.class);
        assertEquals(200, root.getStatusCode().value());
        assertFalse(root.getBody().contains("must-not-be-exposed"));
        assertFalse(root.getBody().contains("components"));
        for (String path : new String[]{"/actuator/env", "/actuator/metrics"}) {
            var unavailable = http.getForEntity(internal(path), String.class);
            assertEquals(404, unavailable.getStatusCode().value());
            assertFalse(unavailable.getBody().contains("propertySources"));
            assertFalse(unavailable.getBody().contains("availableTags"));
        }
    }

    @Test
    void refusingTrafficReturns503AndRecoversWhenReady() {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        try {
            assertEquals(503, http.getForEntity(internal("/actuator/health/readiness"), String.class).getStatusCode().value());
            assertEquals(200, http.getForEntity(internal("/actuator/health/liveness"), String.class).getStatusCode().value());
        } finally { AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC); }
        assertEquals(200, http.getForEntity(internal("/actuator/health/readiness"), String.class).getStatusCode().value());
    }

    private String internal(String path) { return "http://127.0.0.1:" + managementPort + path; }
}

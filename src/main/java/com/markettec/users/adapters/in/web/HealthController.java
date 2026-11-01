package com.markettec.users.adapters.in.web;

import com.markettec.users.application.ports.in.GetHealthQuery;
import com.markettec.users.domain.model.ServiceHealth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final GetHealthQuery getHealthQuery;

    public HealthController(GetHealthQuery getHealthQuery) {
        this.getHealthQuery = getHealthQuery;
    }

    @GetMapping("/health")
    ResponseEntity<HealthResponse> health(HttpServletRequest request) {
        ServiceHealth health = getHealthQuery.getHealth();
        String correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
        return ResponseEntity.ok(new HealthResponse(
                health.status(), health.service(), health.timestamp(), correlationId));
    }

    record HealthResponse(String status, String service, long timestamp, String correlationId) {
    }
}

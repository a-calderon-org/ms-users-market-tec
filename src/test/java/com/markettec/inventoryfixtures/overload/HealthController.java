package com.markettec.inventoryfixtures.overload;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mutation B fixture (Codex reproduction): same simple class name, method name, parameter types,
 * verb and path as the production {@code HealthController#health}; only the {@code params}
 * condition and the package differ. Outside the application's component scan: it is registered
 * only by the mutation test that imports it.
 */
@RestController
public class HealthController {
    @GetMapping(path = "/health", params = "auditProbe")
    ResponseEntity<String> health(HttpServletRequest request) {
        return ResponseEntity.ok("audit-overload");
    }
}

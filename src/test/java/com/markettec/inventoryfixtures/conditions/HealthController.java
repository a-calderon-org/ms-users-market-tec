package com.markettec.inventoryfixtures.conditions;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Third-variant fixture: one method, another verb on the existing path, a second alias pattern and
 * header/consumes conditions. Outside the application's component scan.
 */
@RestController
public class HealthController {
    @RequestMapping(path = {"/health", "/audit-alias"}, method = RequestMethod.POST,
            headers = "X-Audit-Probe", consumes = "application/json")
    ResponseEntity<String> health() {
        return ResponseEntity.ok("audit-conditions");
    }
}

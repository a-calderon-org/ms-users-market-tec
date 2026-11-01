package com.markettec.users.adapters.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class NotFoundController {

    @RequestMapping("/**")
    ResponseEntity<ErrorEnvelope> notFound(HttpServletRequest request) {
        String correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
        ErrorBody error = new ErrorBody(
                "ROUTE_NOT_FOUND",
                "The requested route does not exist.",
                List.of(),
                correlationId,
                Instant.now().getEpochSecond());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorEnvelope(error));
    }

    record ErrorEnvelope(ErrorBody error) {
    }

    record ErrorBody(String code, String message, List<String> details, String correlationId, long timestamp) {
    }
}

package com.markettec.users.adapters.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> handleUnexpected(Exception exception, HttpServletRequest request) {
        String correlationId = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME);
        ErrorBody error = new ErrorBody(
                "INTERNAL_ERROR",
                "The service could not process the request.",
                List.of(),
                correlationId,
                Instant.now().getEpochSecond());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorEnvelope(error));
    }

    record ErrorEnvelope(ErrorBody error) {
    }

    record ErrorBody(String code, String message, List<String> details, String correlationId, long timestamp) {
    }
}

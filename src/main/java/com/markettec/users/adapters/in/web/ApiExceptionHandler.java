package com.markettec.users.adapters.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ErrorEnvelope> handleNotFound(Exception exception, HttpServletRequest request) {
        return safeError(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "The requested route does not exist.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorEnvelope> handleMethodNotAllowed(Exception exception, HttpServletRequest request) {
        return safeError(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "The request method is not supported.", request);
    }

    private ResponseEntity<ErrorEnvelope> safeError(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorEnvelope(new ErrorBody(code, message, List.of(),
                (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME), Instant.now().getEpochSecond())));
    }


    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorEnvelope> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("request_failed", exception);
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

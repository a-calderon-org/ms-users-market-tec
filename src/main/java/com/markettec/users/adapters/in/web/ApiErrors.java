package com.markettec.users.adapters.in.web;

import com.markettec.users.adapters.out.jwt.InternalJwtService.InvalidInternalJwtException;
import com.markettec.users.application.session.EmailDeliveryException;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.domain.model.UserDomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Uniform, non-revealing error envelope for the bearer and Magic Link adapters. */
final class ApiErrors {
    private ApiErrors() { }

    static ResponseEntity<GoogleAuthController.ErrorEnvelope> of(HttpStatus status, String code,
                                                                 HttpServletRequest request, Clock clock) {
        var headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        if (status == HttpStatus.UNAUTHORIZED) headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return ResponseEntity.status(status).headers(headers).body(new GoogleAuthController.ErrorEnvelope(
                new GoogleAuthController.ErrorBody(code, "No se pudo completar la operación.", List.of(),
                        (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME),
                        clock.instant().getEpochSecond())));
    }

    static ResponseEntity<GoogleAuthController.ErrorEnvelope> map(Exception failure,
                                                                  HttpServletRequest request, Clock clock) {
        if (failure instanceof GoogleAuthController.AuthHttpException http)
            return of(http.status(), http.code(), request, clock);
        if (failure instanceof InvalidInternalJwtException)
            return of(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request, clock);
        if (failure instanceof SessionRejectedException rejected) return switch (rejected.reason()) {
            case ATTEMPT_CONSUMED -> of(HttpStatus.CONFLICT, "ATTEMPT_CONSUMED", request, clock);
            case ATTEMPT_EXPIRED -> of(HttpStatus.GONE, "ATTEMPT_EXPIRED", request, clock);
            case IDENTITY_CONFLICT -> of(HttpStatus.UNAUTHORIZED, "AUTH_PROOF_INVALID", request, clock);
            default -> of(HttpStatus.UNAUTHORIZED, "AUTH_PROOF_INVALID", request, clock);
        };
        if (failure instanceof UserDomainException domain) return switch (domain.reason()) {
            case UNAUTHORIZED -> of(HttpStatus.FORBIDDEN, "FORBIDDEN", request, clock);
            case USER_NOT_FOUND -> of(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", request, clock);
            case USER_DEACTIVATED -> of(HttpStatus.FORBIDDEN, "USER_DEACTIVATED", request, clock);
            case IDENTITY_CONFLICT, USER_ALREADY_EXISTS ->
                    of(HttpStatus.CONFLICT, "IDENTITY_CONFLICT", request, clock);
            case CONCURRENT_MODIFICATION -> of(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", request, clock);
        };
        if (failure instanceof EmailDeliveryException)
            return of(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE", request, clock);
        if (failure instanceof IllegalArgumentException)
            return of(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", request, clock);
        throw new IllegalStateException("Unmapped failure", failure);
    }
}

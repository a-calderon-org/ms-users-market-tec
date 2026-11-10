package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.markettec.users.adapters.out.jwt.InternalJwtService.InvalidInternalJwtException;
import com.markettec.users.application.ports.in.VerifiedInternalToken;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.session.PasswordCredentialService;
import com.markettec.users.application.session.PasswordOperationException;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.domain.model.UserDomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/**
 * Bearer-authenticated password management. Ownership derives only from the verified JWT; every mutation also
 * needs the session CSRF proof. The first password additionally needs the one-use grant a confirmed Magic Link
 * leaves on the session; changing it needs the current password.
 */
@RestController
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnExpression("${users.api.http.enabled:false} and ${users.password.enabled:false}")
public final class PasswordApiController {
    private final InternalTokenVerifier jwt;
    private final SessionAuthority sessions;
    private final ObjectProvider<PasswordCredentialService> passwords;
    private final Clock clock;

    public PasswordApiController(InternalTokenVerifier jwt, SessionAuthority sessions,
                                 ObjectProvider<PasswordCredentialService> passwords, Clock clock) {
        this.jwt = jwt;
        this.sessions = sessions;
        this.passwords = passwords;
        this.clock = clock;
    }

    @GetMapping(path = "/v1/me/password", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> status(HttpServletRequest request) {
        var token = jwt.verify(BearerRequests.bearer(request));
        var status = service().status(token.sessionId(), token.userId());
        return ok(HttpStatus.OK, Map.of("configured", status.configured(), "setupAvailable", status.setupAvailable()));
    }

    /** First password. 403 PASSWORD_SETUP_NOT_AUTHORIZED without a live grant; 409 if one already exists. */
    @PostMapping(path = "/v1/me/password", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> setInitial(@RequestBody JsonNode body, HttpServletRequest request) {
        var token = mutating(request);
        requireFields(body, "newPassword");
        service().setInitial(token.sessionId(), token.userId(), body.get("newPassword").asText());
        return ok(HttpStatus.CREATED, Map.of("configured", true));
    }

    /** Change with the current password; every other session of the account is revoked. */
    @PutMapping(path = "/v1/me/password", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> change(@RequestBody JsonNode body, HttpServletRequest request) {
        var token = mutating(request);
        requireFields(body, "currentPassword", "newPassword");
        service().change(token.sessionId(), token.userId(), body.get("currentPassword").asText(),
                body.get("newPassword").asText());
        return ok(HttpStatus.OK, Map.of("configured", true, "otherSessionsRevoked", true));
    }

    private PasswordCredentialService service() {
        var service = passwords.getIfAvailable();
        if (service == null)
            throw new GoogleAuthController.AuthHttpException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE");
        return service;
    }

    private VerifiedInternalToken mutating(HttpServletRequest request) {
        var token = jwt.verify(BearerRequests.bearer(request));
        try {
            sessions.requireCsrf(token.sessionId(), token.userId(), request.getHeader("X-CSRF-Token"));
        } catch (SessionRejectedException rejected) {
            throw new GoogleAuthController.AuthHttpException(HttpStatus.FORBIDDEN, "CSRF_INVALID");
        }
        return token;
    }

    private static void requireFields(JsonNode body, String... names) {
        if (!EmailAuthController.onlyFields(body, names))
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    private static ResponseEntity<?> ok(HttpStatus status, Object body) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
    }

    @ExceptionHandler({GoogleAuthController.AuthHttpException.class, InvalidInternalJwtException.class,
            SessionRejectedException.class, PasswordOperationException.class, UserDomainException.class,
            IllegalArgumentException.class})
    public ResponseEntity<?> failed(Exception failure, HttpServletRequest request) {
        return ApiErrors.map(failure, request, clock);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(HttpMessageNotReadableException ignored, HttpServletRequest request) {
        return ApiErrors.of(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", request, clock);
    }
}

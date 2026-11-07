package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.markettec.users.adapters.out.jwt.InternalJwtService.InvalidInternalJwtException;
import com.markettec.users.application.ports.in.VerifiedInternalToken;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.session.*;
import com.markettec.users.domain.model.ExternalIdentity;
import com.markettec.users.domain.model.IdentityProvider;
import com.markettec.users.domain.model.UserDomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/**
 * Bearer-authenticated session activity, recent-authentication proofs and authentication methods.
 * Every mutation also needs the session CSRF proof; ownership always derives from the verified JWT.
 */
@RestController
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.api.http", name = "enabled", havingValue = "true")
public final class AuthApiController {
    private final InternalTokenVerifier jwt;
    private final SessionAuthority sessions;
    private final RecentProofService proofs;
    private final ObjectProvider<AuthMethodsService> methods;
    private final Clock clock;

    public AuthApiController(InternalTokenVerifier jwt, SessionAuthority sessions, RecentProofService proofs,
                             ObjectProvider<AuthMethodsService> methods, Clock clock) {
        this.jwt = jwt;
        this.sessions = sessions;
        this.proofs = proofs;
        this.methods = methods;
        this.clock = clock;
    }

    /**
     * B03: only server-verified business activity may extend a session, and no producer of that
     * evidence exists yet. The caller is authenticated, then refused; deadlines are never touched.
     */
    @PostMapping(path = "/v1/auth/session/activity", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> activity(HttpServletRequest request) {
        mutating(request);
        throw new GoogleAuthController.AuthHttpException(HttpStatus.FORBIDDEN, "ACTIVITY_EVIDENCE_REQUIRED");
    }

    @PostMapping(path = "/v1/auth/proofs/attempts", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> beginProof(@RequestBody JsonNode body, HttpServletRequest request) {
        var token = mutating(request);
        requireFields(body, "method", "operation", "target");
        RecentAttempt.Method method = switch (body.get("method").asText()) {
            case "google" -> RecentAttempt.Method.GOOGLE;
            case "institutional_email" -> RecentAttempt.Method.INSTITUTIONAL_EMAIL;
            default -> throw invalid();
        };
        var attempt = proofs.begin(token.sessionId(), token.userId(), method,
                body.get("operation").asText(), body.get("target").asText());
        return ok(new AttemptResponse(attempt.attemptId(), body.get("method").asText(), attempt.operation(),
                attempt.target(), attempt.nonce(), attempt.expiresAt().getEpochSecond()));
    }

    @PostMapping(path = "/v1/auth/proofs/google", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> googleProof(@RequestBody JsonNode body, HttpServletRequest request) {
        var token = mutating(request);
        requireFields(body, "attemptId", "credential");
        attemptId(body);
        if (!proofs.googleAvailable()) throw unavailable();
        proofs.proveGoogle(token.sessionId(), token.userId(), body.get("attemptId").asText(),
                body.get("credential").asText());
        return ok(Map.of("verified", true));
    }

    @PostMapping(path = "/v1/auth/proofs/email/requests", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> emailProofRequest(@RequestBody JsonNode body, HttpServletRequest request) {
        var token = mutating(request);
        requireFields(body, "attemptId");
        attemptId(body);
        if (!proofs.emailAvailable()) throw unavailable();
        proofs.requestEmail(token.sessionId(), token.userId(), body.get("attemptId").asText());
        return ResponseEntity.accepted().header("Cache-Control", "no-store").body(Map.of("status", "accepted"));
    }

    @PostMapping(path = "/v1/auth/proofs/email/confirm", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> emailProofConfirm(@RequestBody JsonNode body, HttpServletRequest request) {
        var token = mutating(request);
        requireFields(body, "attemptId", "secret");
        attemptId(body);
        if (!body.get("secret").asText().matches("[0-9a-f]{64}")) throw invalid();
        if (!proofs.emailAvailable()) throw unavailable();
        proofs.confirmEmail(token.sessionId(), token.userId(), body.get("attemptId").asText(),
                body.get("secret").asText());
        return ok(Map.of("verified", true));
    }

    @GetMapping(path = "/v1/me/auth-methods", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listMethods(HttpServletRequest request) {
        var token = jwt.verify(BearerRequests.bearer(request));
        var service = methods.getIfAvailable();
        if (service == null) throw unavailable();
        List<MethodResponse> items = service.list(token.sessionId(), token.userId()).stream()
                .map(AuthApiController::method).toList();
        return ok(Map.of("methods", items));
    }

    /**
     * Two independent recent proofs: control of the current method ({@code currentAttemptId}) and of the
     * new one ({@code newAttemptId}, plus the Google {@code credential} when linking Google).
     */
    @PostMapping(path = "/v1/me/auth-methods", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> linkMethod(@RequestBody JsonNode body, HttpServletRequest request) {
        var token = mutating(request);
        boolean hasCredential = body != null && body.has("credential");
        if (body == null || !body.isObject() || !body.path("provider").isTextual()
                || !body.path("currentAttemptId").isTextual() || !body.path("newAttemptId").isTextual()
                || body.size() != (hasCredential ? 4 : 3) || (hasCredential && !body.get("credential").isTextual())
                || !body.get("currentAttemptId").asText().matches("[0-9a-f]{32}")
                || !body.get("newAttemptId").asText().matches("[0-9a-f]{32}")
                || body.get("currentAttemptId").asText().equals(body.get("newAttemptId").asText()))
            throw invalid();
        IdentityProvider provider = switch (body.get("provider").asText()) {
            case "google" -> IdentityProvider.GOOGLE;
            case "institutional_email" -> IdentityProvider.INSTITUTIONAL_EMAIL;
            default -> throw invalid();
        };
        if ((provider == IdentityProvider.GOOGLE) != hasCredential) throw invalid();
        var service = methods.getIfAvailable();
        if (service == null) throw unavailable();
        var linked = service.link(token.sessionId(), token.userId(), provider,
                body.get("currentAttemptId").asText(), body.get("newAttemptId").asText(),
                hasCredential ? body.get("credential").asText() : null);
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store").body(method(linked));
    }

    /** Verified JWT subject plus the session-bound CSRF synchronizer. */
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
        if (!EmailAuthController.onlyFields(body, names)) throw invalid();
    }

    private static void attemptId(JsonNode body) {
        if (!body.get("attemptId").asText().matches("[0-9a-f]{32}")) throw invalid();
    }

    private static GoogleAuthController.AuthHttpException invalid() {
        return new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    private static GoogleAuthController.AuthHttpException unavailable() {
        return new GoogleAuthController.AuthHttpException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE");
    }

    private static MethodResponse method(ExternalIdentity identity) {
        return new MethodResponse(identity.provider() == IdentityProvider.GOOGLE ? "google" : "institutional_email",
                identity.verifiedEmail().address(), identity.status().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }

    @ExceptionHandler({GoogleAuthController.AuthHttpException.class, InvalidInternalJwtException.class,
            SessionRejectedException.class, UserDomainException.class, EmailDeliveryException.class,
            IllegalArgumentException.class})
    public ResponseEntity<?> failed(Exception failure, HttpServletRequest request) {
        return ApiErrors.map(failure, request, clock);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(HttpMessageNotReadableException ignored, HttpServletRequest request) {
        return ApiErrors.of(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", request, clock);
    }

    public record AttemptResponse(String attemptId, String method, String operation, String target,
                                  String nonce, long expiresAt) { }
    public record MethodResponse(String provider, String email, String status) { }
}

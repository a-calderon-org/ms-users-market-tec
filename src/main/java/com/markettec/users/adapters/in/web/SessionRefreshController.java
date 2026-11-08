package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.markettec.users.adapters.out.jwt.InternalJwtService;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.domain.model.UserDomainException;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Opt-in B3 refresh adapter; its transaction and credentials are independent of Google B1. */
@RestController
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.session.refresh", name = "enabled", havingValue = "true")
public final class SessionRefreshController {
    private static final Logger AUDIT = LoggerFactory.getLogger(SessionRefreshController.class);
    private static final String REFRESH = "__Host-mtec-refresh";
    private final SessionAuthority authority;
    private final InternalJwtService jwt;
    private final Clock clock;
    private final Set<String> origins;

    public SessionRefreshController(SessionAuthority authority, InternalJwtService jwt, Clock clock,
            @Value("${users.session.http.allowed-origins:}") String allowedOrigins,
            @Value("${users.session.http.public-api-origin:}") String publicApiOrigin,
            @Value("${users.session.http.https-topology-verified:false}") boolean topologyVerified) {
        this.authority = authority;
        this.jwt = jwt;
        this.clock = clock;
        this.origins = Arrays.stream(allowedOrigins.split(",", -1)).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (!topologyVerified || Arrays.stream(allowedOrigins.split(",", -1)).anyMatch(String::isBlank)
                || !validOrigin(publicApiOrigin) || origins.isEmpty() || origins.stream().anyMatch(o -> !validOrigin(o)))
            throw new IllegalArgumentException("Explicit verified HTTPS session topology is required");
    }

    @PostMapping(path = "/v1/auth/refresh", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthResponse> rotate(@RequestBody JsonNode body,
            HttpServletRequest request) {
        String origin = allowedOrigin(request);
        if (!request.isSecure()) throw new RefreshHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        Enumeration<String> clients = request.getHeaders("X-MTEC-Client");
        if (!clients.hasMoreElements() || !"web".equals(clients.nextElement()) || clients.hasMoreElements())
            throw new RefreshHttpException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        if (body == null || !body.isObject() || body.size() != 1
                || !body.path("mode").isTextual() || !"rotate".equals(body.path("mode").asText()))
            throw new RefreshHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        String refresh = refreshCookie(request);
        if (refresh == null) {
            auditInvalidProof();
            throw new RefreshHttpException(HttpStatus.UNAUTHORIZED, "REFRESH_INVALID");
        }
        Enumeration<String> csrfHeaders = request.getHeaders("X-CSRF-Token");
        String csrf = csrfHeaders.hasMoreElements() ? csrfHeaders.nextElement() : null;
        if (csrf == null || !csrf.matches("[0-9a-f]{64}") || csrfHeaders.hasMoreElements()) {
            auditInvalidProof();
            throw new RefreshHttpException(HttpStatus.UNAUTHORIZED, "REFRESH_INVALID");
        }
        var result = authority.refreshWithSnapshot(refresh, csrf, jwt);
        var tokens = result.tokens();
        var session = result.session();
        var user = result.user();
        var profile = user.profile();
        long deadline = session.deadline().getEpochSecond();
        long now = result.serverTime().getEpochSecond();
        long expiry;
        try { expiry = SignedJWT.parse(tokens.jwt()).getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond(); }
        catch (ParseException invalid) { throw new IllegalStateException("Internal JWT could not be mapped"); }
        var snapshot = new SessionBootstrapController.SessionSnapshot(session.sessionId(), "active", now,
                session.createdAt().getEpochSecond(), session.requireRenewedAt().getEpochSecond(),
                session.lastActivityAt().getEpochSecond(), session.renewableUntil().getEpochSecond(),
                session.idleUntil().getEpochSecond(), session.absoluteUntil().getEpochSecond(),
                deadline, Math.max(session.createdAt().getEpochSecond(), deadline - 300));
        var authenticated = new SessionBootstrapController.AuthenticatedUser(user.userId().value(),
                profile.correo().address(), profile.details().nombre(), profile.fotoPerfil().toString());
        var cookie = ResponseCookie.from(REFRESH, tokens.refreshToken()).secure(true).httpOnly(true)
                .path("/").sameSite("Lax")
                .maxAge(Math.max(0, Duration.between(result.serverTime(), session.deadline()).getSeconds()))
                .build().toString();
        return ResponseEntity.ok().headers(cors(origin)).header(HttpHeaders.SET_COOKIE, cookie)
                .body(new AuthResponse(tokens.jwt(), "Bearer", expiry,
                        authenticated, snapshot, tokens.csrfToken()));
    }

    public ResponseEntity<Void> preflight(HttpServletRequest request) {
        String origin = allowedOrigin(request);
        if (!request.isSecure() || !"POST".equals(request.getHeader("Access-Control-Request-Method")))
            throw new RefreshHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        String requested = request.getHeader("Access-Control-Request-Headers");
        if (requested == null || requested.isBlank() || Arrays.stream(requested.split(",", -1))
                .map(String::trim).map(String::toLowerCase)
                .anyMatch(h -> !Set.of("content-type", "x-mtec-client", "x-csrf-token", "x-correlation-id").contains(h)))
            throw new RefreshHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        return ResponseEntity.noContent().headers(cors(origin))
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        "Content-Type, X-MTEC-Client, X-CSRF-Token, X-Correlation-ID").build();
    }

    @ExceptionHandler({RefreshHttpException.class, SessionRejectedException.class,
            UserDomainException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<SessionBootstrapController.ErrorEnvelope> rejected(Exception rejected,
            HttpServletRequest request) {
        HttpStatus status = rejected instanceof RefreshHttpException failure ? failure.status
                : rejected instanceof HttpMessageNotReadableException ? HttpStatus.BAD_REQUEST : HttpStatus.UNAUTHORIZED;
        String code = rejected instanceof RefreshHttpException failure ? failure.code
                : rejected instanceof HttpMessageNotReadableException ? "INVALID_REQUEST" : "REFRESH_INVALID";
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.add(HttpHeaders.VARY, "Origin");
        String origin = singleOrigin(request);
        if (origin != null && origins.contains(origin)) headers.putAll(cors(origin));
        return ResponseEntity.status(status).headers(headers).body(new SessionBootstrapController.ErrorEnvelope(
                new SessionBootstrapController.ErrorBody(code, "No se pudo completar la operación.", List.of(),
                        (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME),
                        clock.instant().getEpochSecond())));
    }

    private String allowedOrigin(HttpServletRequest request) {
        String origin = singleOrigin(request);
        if (origin == null || !origins.contains(origin))
            throw new RefreshHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        return origin;
    }

    private static String singleOrigin(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("Origin");
        if (values == null || !values.hasMoreElements()) return null;
        String first = values.nextElement();
        return values.hasMoreElements() ? null : first;
    }

    private static String refreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        String value = null;
        boolean seen = false;
        for (Cookie cookie : cookies) if (REFRESH.equals(cookie.getName())) {
            if (seen) return null;
            seen = true;
            value = cookie.getValue();
        }
        return value != null && value.matches("[0-9a-f]{64}") ? value : null;
    }

    private static void auditInvalidProof() {
        AUDIT.atInfo().addKeyValue("eventType", "session_refresh")
                .addKeyValue("outcome", "proof_invalid").addKeyValue("policyVersion", "C-1")
                .log("refresh_audit");
    }

    private static HttpHeaders cors(String origin) {
        var headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.setAccessControlAllowOrigin(origin);
        headers.setAccessControlAllowCredentials(true);
        headers.setAccessControlExposeHeaders(List.of("X-Correlation-ID"));
        headers.add(HttpHeaders.VARY, "Origin");
        return headers;
    }

    private static boolean validOrigin(String value) {
        return BrowserOrigins.isCanonicalHttpsOrigin(value);
    }

    private static final class RefreshHttpException extends RuntimeException {
        final HttpStatus status;
        final String code;
        RefreshHttpException(HttpStatus status, String code) {
            super("Refresh request rejected"); this.status = status; this.code = code;
        }
    }

    public record AuthResponse(String token, String tokenType, long expiresAt,
            SessionBootstrapController.AuthenticatedUser user,
            SessionBootstrapController.SessionSnapshot session, String csrfToken) {
        @Override public String toString() { return "AuthResponse[credentials=REDACTED]"; }
    }
}

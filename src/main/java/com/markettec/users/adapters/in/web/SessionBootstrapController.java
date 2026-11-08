package com.markettec.users.adapters.in.web;

import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.domain.model.UserDomainException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cookie-only B3 bootstrap; independent of the Google B1 HTTP adapter. */
@RestController
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.session.http", name = "enabled", havingValue = "true")
public final class SessionBootstrapController {
    private static final String REFRESH = "__Host-mtec-refresh";
    private final SessionAuthority authority;
    private final Clock clock;
    private final Set<String> origins;

    @Autowired
    public SessionBootstrapController(SessionAuthority authority, Clock clock,
            @Value("${users.session.http.allowed-origins:}") String allowedOrigins,
            @Value("${users.session.http.public-api-origin:}") String publicApiOrigin,
            @Value("${users.session.http.https-topology-verified:false}") boolean topologyVerified) {
        this.authority = authority;
        this.clock = clock;
        this.origins = Arrays.stream(allowedOrigins.split(",", -1)).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (!topologyVerified || Arrays.stream(allowedOrigins.split(",", -1)).anyMatch(String::isBlank)
                || !validOrigin(publicApiOrigin) || origins.isEmpty()
                || origins.stream().anyMatch(o -> !validOrigin(o)))
            throw new IllegalArgumentException("Explicit verified HTTPS session topology is required");
    }

    @GetMapping(path = "/v1/auth/session", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionResponse> get(HttpServletRequest request) {
        String origin = allowedOrigin(request);
        if (!request.isSecure()) throw new SessionHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        if (!"web".equals(request.getHeader("X-MTEC-Client")))
            throw new SessionHttpException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        String refresh = refreshCookie(request);
        if (refresh == null) throw new SessionHttpException(HttpStatus.UNAUTHORIZED, "REFRESH_INVALID");
        var result = authority.bootstrap(refresh);
        var session = result.session();
        var user = result.user();
        var profile = user.profile();
        long deadline = session.deadline().getEpochSecond();
        var snapshot = new SessionSnapshot(session.sessionId(), "active",
                result.serverTime().getEpochSecond(), session.createdAt().getEpochSecond(),
                session.requireRenewedAt().getEpochSecond(), session.lastActivityAt().getEpochSecond(),
                session.renewableUntil().getEpochSecond(), session.idleUntil().getEpochSecond(),
                session.absoluteUntil().getEpochSecond(), deadline,
                Math.max(session.createdAt().getEpochSecond(), deadline - 300));
        var authenticated = new AuthenticatedUser(user.userId().value(),
                profile.correo().address(), profile.details().nombre(), profile.fotoPerfil().toString());
        return ResponseEntity.ok().headers(cors(origin))
                .body(new SessionResponse(snapshot, authenticated, result.csrfToken()));
    }

    public ResponseEntity<Void> preflight(HttpServletRequest request) {
        String origin = allowedOrigin(request);
        if (!request.isSecure()) throw new SessionHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        if (!"GET".equals(request.getHeader("Access-Control-Request-Method")))
            throw new SessionHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        String requested = request.getHeader("Access-Control-Request-Headers");
        if (requested == null || requested.isBlank() || Arrays.stream(requested.split(",", -1))
                .map(String::trim).map(String::toLowerCase)
                .anyMatch(h -> !Set.of("x-mtec-client", "x-correlation-id").contains(h)))
            throw new SessionHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        return ResponseEntity.noContent().headers(cors(origin))
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "X-MTEC-Client, X-Correlation-ID").build();
    }

    @ExceptionHandler({SessionHttpException.class, SessionRejectedException.class, UserDomainException.class})
    public ResponseEntity<ErrorEnvelope> rejected(Exception rejected, HttpServletRequest request) {
        HttpStatus status = rejected instanceof SessionHttpException failure
                ? failure.status : HttpStatus.UNAUTHORIZED;
        String code = rejected instanceof SessionHttpException failure
                ? failure.code : "REFRESH_INVALID";
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.add(HttpHeaders.VARY, "Origin");
        String origin = singleOrigin(request);
        if (origin != null && origins.contains(origin)) headers.putAll(cors(origin));
        return ResponseEntity.status(status).headers(headers).body(new ErrorEnvelope(
                new ErrorBody(code, "No se pudo completar la operación.", List.of(),
                        (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME),
                        clock.instant().getEpochSecond())));
    }

    private String allowedOrigin(HttpServletRequest request) {
        String origin = singleOrigin(request);
        // Without Origin, this adapter has no independently verified public-origin evidence.
        if (origin == null || !origins.contains(origin))
            throw new SessionHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
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
        for (Cookie cookie : cookies) {
            if (REFRESH.equals(cookie.getName())) {
                if (seen) return null;
                seen = true;
                value = cookie.getValue();
            }
        }
        return value != null && value.matches("[0-9a-f]{64}") ? value : null;
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

    public record AuthenticatedUser(String userId, String email, String nombre, String fotoPerfil) { }
    public record SessionSnapshot(String sessionId, String status, long serverTime, long authenticatedAt,
            long renewedAt, long lastActivityAt, long windowExpiresAt, long inactivityExpiresAt,
            long absoluteExpiresAt, long expiresAt, long warningAt) { }
    public record ErrorEnvelope(ErrorBody error) { }
    public record ErrorBody(String code, String message, List<String> details, String correlationId,
            long timestamp) { }

    public record SessionResponse(SessionSnapshot session,
            AuthenticatedUser user, String csrfToken) {
        @Override public String toString() { return "SessionResponse[credentials=REDACTED]"; }
    }

    private static final class SessionHttpException extends RuntimeException {
        final HttpStatus status;
        final String code;
        SessionHttpException(HttpStatus status, String code) {
            super("Session request rejected");
            this.status = status;
            this.code = code;
        }
    }
}

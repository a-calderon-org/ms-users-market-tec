package com.markettec.users.adapters.in.web;

import com.markettec.users.adapters.out.google.InvalidGoogleTokenException;
import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.session.GoogleLoginService;
import com.markettec.users.application.session.GoogleRegistrationService;
import com.markettec.users.application.session.Session;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.domain.model.UserId;
import com.markettec.users.domain.model.Email;
import com.nimbusds.jwt.SignedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import com.markettec.users.domain.model.UserDomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import java.util.stream.Collectors;

/** Browser-only Draft B1 adapter. All credential verification and issuance remain internal. */
@RestController
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.google.http", name = "enabled", havingValue = "true")
public final class GoogleAuthController {
    private static final String ATTEMPT = "__Host-mtec-attempt";
    private static final String REFRESH = "__Host-mtec-refresh";
    private final GoogleLoginService login;
    private final GoogleRegistrationService registration;
    private final SessionAuthority sessions;
    private final UserRepository users;
    private final Clock clock;
    private final Set<String> origins;
    private final boolean registrationEnabled;

    @ExceptionHandler(AuthHttpException.class)
    public ResponseEntity<?> authError(AuthHttpException rejected, HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        HttpHeaders headers = origin != null && origins.contains(origin) ? cors(origin) : new HttpHeaders();
        headers.setCacheControl("no-store");
        if (rejected.status() == HttpStatus.UNAUTHORIZED)
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return ResponseEntity.status(rejected.status()).headers(headers).body(new ErrorEnvelope(
                new ErrorBody(rejected.code(), "No se pudo completar la operación.", List.of(),
                        (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME),
                        clock.instant().getEpochSecond())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(HttpMessageNotReadableException ignored, HttpServletRequest request) {
        return authError(new AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"), request);
    }

    @Autowired
    public GoogleAuthController(GoogleLoginService login, GoogleRegistrationService registration,
            SessionAuthority sessions, UserRepository users, Clock clock,
            @Value("${users.google.http.allowed-origins:}") String allowedOrigins,
            @Value("${users.google.http.public-api-origin:}") String publicApiOrigin,
            @Value("${users.google.http.same-site-verified:false}") boolean sameSiteVerified,
            @Value("${users.google.registration.enabled:false}") boolean registrationEnabled) {
        this(login, registration, sessions, users, clock,
                Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet()), registrationEnabled);
        if (!sameSiteVerified || !validOrigin(publicApiOrigin))
            throw new IllegalArgumentException("Verified HTTPS same-site browser topology is required");
    }

    public GoogleAuthController(GoogleLoginService login, GoogleRegistrationService registration,
            SessionAuthority sessions, UserRepository users, Clock clock, String allowedOrigins,
            String publicApiOrigin, boolean sameSiteVerified) {
        this(login, registration, sessions, users, clock, allowedOrigins, publicApiOrigin,
                sameSiteVerified, false);
    }

    public GoogleAuthController(GoogleLoginService login, GoogleRegistrationService registration,
            SessionAuthority sessions, UserRepository users, Clock clock, Set<String> origins) {
        this(login, registration, sessions, users, clock, origins, false);
    }

    public GoogleAuthController(GoogleLoginService login, GoogleRegistrationService registration,
            SessionAuthority sessions, UserRepository users, Clock clock, Set<String> origins,
            boolean registrationEnabled) {
        this.login = login;
        this.registration = registration;
        this.sessions = sessions;
        this.users = users;
        this.clock = clock;
        if (origins == null || origins.isEmpty() || origins.stream().anyMatch(o -> !validOrigin(o)))
            throw new IllegalArgumentException("Explicit HTTPS browser origins are required");
        this.origins = Set.copyOf(origins);
        this.registrationEnabled = registrationEnabled;
    }

    @PostMapping(path = "/v1/auth/attempts", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> begin(@RequestBody JsonNode body, HttpServletRequest request) {
        var headers = guard(request, false);
        if (!fields(body, "method", "purpose") || !text(body, "method", "google")
                || (body.has("purpose") && !text(body, "purpose", "login") && !text(body, "purpose", "register")))
            throw new AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        boolean register = text(body, "purpose", "register");
        if (register && !registrationEnabled)
            throw new AuthHttpException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE");
        var challenge = register ? registration.begin() : login.begin();
        headers.add(HttpHeaders.SET_COOKIE, cookie(ATTEMPT, challenge.browserSecret(), 300));
        return ResponseEntity.ok().headers(headers).body(new AttemptResponse(challenge.attemptId(), "google",
                register ? "register" : "login", challenge.expiresAt().getEpochSecond(),
                attemptCsrf(challenge.browserSecret()), challenge.nonce()));
    }

    @PostMapping(path = "/v1/auth/google", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@RequestBody JsonNode body, HttpServletRequest request) {
        return complete(body, request, false);
    }

    @PostMapping(path = "/v1/auth/google/registrations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> register(@RequestBody JsonNode body, HttpServletRequest request) {
        if (!registrationEnabled)
            throw new AuthHttpException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE");
        return complete(body, request, true);
    }

    @PostMapping(path = "/v1/auth/email/requests", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> requestEmail(@RequestBody JsonNode body, HttpServletRequest request) {
        if (!registrationEnabled)
            throw new AuthHttpException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE");
        var headers = guard(request, true);
        if (!fields(body, "attemptId", "email") || !body.path("attemptId").isTextual()
                || !body.path("attemptId").asText().matches("[0-9a-f]{32}")
                || !body.path("email").isTextual() || body.path("email").asText().length() > 254)
            throw new AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        try {
            String requestedEmail = new Email(body.path("email").asText()).normalized();
            registration.requestLink(body.path("attemptId").asText(), browserProof(request), requestedEmail);
            return ResponseEntity.accepted().headers(headers).body(new EmailAccepted("accepted",
                    "Si la solicitud es elegible, recibirás un enlace de acceso."));
        } catch (IllegalArgumentException malformed) {
            throw new AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        } catch (SessionRejectedException rejected) {
            throw mapped(rejected);
        }
    }

    @PostMapping(path = "/v1/auth/email/confirm", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> confirmEmail(@RequestBody JsonNode body, HttpServletRequest request) {
        if (!registrationEnabled)
            throw new AuthHttpException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE");
        var headers = guard(request, true);
        if (!fields(body, "attemptId", "token") || !body.path("attemptId").isTextual()
                || !body.path("attemptId").asText().matches("[0-9a-f]{32}")
                || !body.path("token").isTextual() || !body.path("token").asText().matches("[0-9a-f]{64}"))
            throw new AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        try {
            return authResponse(registration.confirm(body.path("attemptId").asText(),
                    browserProof(request), body.path("token").asText()), headers);
        } catch (SessionRejectedException rejected) {
            throw mapped(rejected);
        } catch (ParseException impossible) {
            throw new IllegalStateException("Internal token could not be mapped");
        }
    }

    private ResponseEntity<?> complete(JsonNode body, HttpServletRequest request, boolean register) {
        var headers = guard(request, true);
        if (!fields(body, "attemptId", "credential") || !body.path("attemptId").isTextual()
                || !body.path("attemptId").asText().matches("[0-9a-f]{32}")
                || !body.path("credential").isTextual() || body.path("credential").asText().isBlank()
                || body.path("credential").asText().length() > 16_384)
            throw new AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        String secret = browserProof(request);
        try {
            String id = body.get("attemptId").asText();
            if (register || (registrationEnabled && registration.isRegistrationAttempt(id))) {
                if (!registrationEnabled)
                    throw new AuthHttpException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE");
                Instant expiresAt = registration.prepare(id, secret, body.get("credential").asText(),
                        (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE_NAME));
                return ResponseEntity.accepted().headers(headers).body(new MailboxVerificationRequired(
                        "mailbox-verification-required", id, expiresAt.getEpochSecond()));
            }
            var result = login.complete(id, secret, body.get("credential").asText());
            return authResponse(result, headers);
        } catch (SessionRejectedException rejected) {
            throw mapped(rejected);
        } catch (InvalidGoogleTokenException | UserDomainException rejected) {
            throw new AuthHttpException(HttpStatus.UNAUTHORIZED, "AUTH_PROOF_INVALID");
        } catch (ParseException impossible) {
            throw new IllegalStateException("Internal token could not be mapped");
        }
    }

    private ResponseEntity<?> authResponse(GoogleLoginService.Result result, HttpHeaders headers) throws ParseException {
            var session = sessions.current(result.sessionId()).orElseThrow(SessionRejectedException::new);
            var user = users.findById(new UserId(result.userId())).orElseThrow(SessionRejectedException::new);
            user.requireActive();
            long expiresAt = SignedJWT.parse(result.jwt()).getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond();
            headers.add(HttpHeaders.SET_COOKIE, cookie(ATTEMPT, "", 0));
            headers.add(HttpHeaders.SET_COOKIE, cookie(REFRESH, result.refreshToken(),
                    Math.max(0, session.deadline().getEpochSecond() - clock.instant().getEpochSecond())));
            var profile = user.profile();
            var authenticatedUser = new AuthenticatedUser(result.userId(), profile.correo().address(),
                    profile.details().nombre(), profile.fotoPerfil().toString());
            long deadline = session.deadline().getEpochSecond();
            var snapshot = new SessionSnapshot(session.sessionId(), "active", clock.instant().getEpochSecond(),
                    session.createdAt().getEpochSecond(), session.createdAt().getEpochSecond(),
                    session.lastActivityAt().getEpochSecond(), session.renewableUntil().getEpochSecond(),
                    session.idleUntil().getEpochSecond(), session.absoluteUntil().getEpochSecond(),
                    deadline, Math.max(session.createdAt().getEpochSecond(), deadline - 300));
            return ResponseEntity.ok().headers(headers).body(new AuthResponse(result.jwt(), "Bearer",
                    expiresAt, authenticatedUser, snapshot, result.csrfToken()));
    }

    private static AuthHttpException mapped(SessionRejectedException rejected) {
            return switch (rejected.reason()) {
                case ATTEMPT_CONSUMED -> new AuthHttpException(HttpStatus.CONFLICT, "ATTEMPT_CONSUMED");
                case ATTEMPT_EXPIRED -> new AuthHttpException(HttpStatus.GONE, "ATTEMPT_EXPIRED");
                case IDENTITY_CONFLICT -> new AuthHttpException(HttpStatus.UNAUTHORIZED, "AUTH_PROOF_INVALID");
                default -> new AuthHttpException(HttpStatus.UNAUTHORIZED, "AUTH_PROOF_INVALID");
            };
    }

    private String browserProof(HttpServletRequest request) {
        String secret = cookieValue(request, ATTEMPT);
        String csrf = request.getHeader("X-CSRF-Token");
        if (secret == null || !secret.matches("[0-9a-f]{64}") || csrf == null
                || !equal(attemptCsrf(secret), csrf))
            throw new AuthHttpException(HttpStatus.FORBIDDEN, "CSRF_INVALID");
        return secret;
    }

    public ResponseEntity<Void> preflight(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String method = request.getHeader("Access-Control-Request-Method");
        String requested = request.getHeader("Access-Control-Request-Headers");
        if (origin == null || !origins.contains(origin) || !"POST".equals(method) || requested == null
                || Arrays.stream(requested.split(",")).map(String::trim).map(String::toLowerCase)
                    .anyMatch(h -> !Set.of("content-type", "x-mtec-client", "x-csrf-token", "x-correlation-id").contains(h)))
            throw new AuthHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        return ResponseEntity.noContent().headers(cors(origin)).header(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        "Content-Type, X-MTEC-Client, X-CSRF-Token, X-Correlation-ID").build();
    }

    private HttpHeaders guard(HttpServletRequest request, boolean continuation) {
        String origin = request.getHeader("Origin");
        if (origin == null || !origins.contains(origin))
            throw new AuthHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        if (!"web".equals(request.getHeader("X-MTEC-Client")))
            throw new AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        return cors(origin);
    }
    private static HttpHeaders cors(String origin) {
        var h = new HttpHeaders();
        h.setCacheControl("no-store");
        h.setAccessControlAllowOrigin(origin);
        h.setAccessControlAllowCredentials(true);
        h.setAccessControlExposeHeaders(List.of("X-CSRF-Token", "X-Correlation-ID"));
        h.add(HttpHeaders.VARY, "Origin");
        return h;
    }
    private static boolean fields(JsonNode body, String... allowed) {
        if (body == null || !body.isObject()) return false;
        var names = Set.of(allowed);
        var entries = body.fieldNames();
        while (entries.hasNext()) if (!names.contains(entries.next())) return false;
        for (String name : allowed) if ("method".equals(name) && !body.has(name)) return false;
        if (names.contains("credential")) return body.has("credential") && body.has("attemptId");
        return true;
    }
    private static boolean text(JsonNode body, String name, String value) {
        return body.path(name).isTextual() && value.equals(body.path(name).asText());
    }
    private static String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        String found = null;
        for (Cookie cookie : request.getCookies()) if (name.equals(cookie.getName())) {
            if (found != null) return null;
            found = cookie.getValue();
        }
        return found;
    }
    private static String cookie(String name, String value, long seconds) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(true).path("/")
                .sameSite("Lax").maxAge(seconds).build().toString();
    }
    private static String attemptCsrf(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("mtec-attempt-csrf:" + secret).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
    private static boolean validOrigin(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getPort() == -1
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && !uri.getHost().equals("localhost") && !uri.getHost().endsWith(".invalid");
        } catch (RuntimeException invalid) { return false; }
    }
    public record AttemptResponse(String attemptId, String method, String purpose, long expiresAt,
            String csrfToken, String nonce) {
        @Override public String toString() { return "AttemptResponse[credentials=REDACTED]"; }
    }
    public record AuthenticatedUser(String userId, String email, String nombre, String fotoPerfil) { }
    public record MailboxVerificationRequired(String status, String attemptId, long expiresAt) { }
    public record EmailAccepted(String status, String message) { }
    public record SessionSnapshot(String sessionId, String status, long serverTime, long authenticatedAt,
            long renewedAt, long lastActivityAt, long windowExpiresAt, long inactivityExpiresAt,
            long absoluteExpiresAt, long expiresAt, long warningAt) { }
    public record AuthResponse(String token, String tokenType, long expiresAt, AuthenticatedUser user,
            SessionSnapshot session, String csrfToken) {
        @Override public String toString() { return "AuthResponse[credentials=REDACTED]"; }
    }
    public record ErrorEnvelope(ErrorBody error) { }
    public record ErrorBody(String code, String message, List<String> details, String correlationId,
            long timestamp) { }
    public static final class AuthHttpException extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        AuthHttpException(HttpStatus status, String code) { super("Authentication request rejected"); this.status=status; this.code=code; }
        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}

package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.session.EmailLoginService;
import com.markettec.users.application.session.GoogleLoginService;
import com.markettec.users.application.session.EmailDeliveryException;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.domain.model.Email;
import com.markettec.users.domain.model.UserDomainException;
import com.markettec.users.domain.model.UserId;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Institutional Magic Link login. Same browser origin controls as the Google adapter; the link is
 * only redeemed by an explicit POST. Without the {@code users.email.enabled} configuration the
 * routes fail closed with 503 (no delivery is simulated).
 */
@RestController
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.google.http", name = "enabled", havingValue = "true")
public final class EmailAuthController {
    private static final String REFRESH = "__Host-mtec-refresh";
    private static final String ATTEMPT = "__Host-mtec-email-attempt";
    private final ObjectProvider<EmailLoginService> service;
    private final SessionAuthority sessions;
    private final UserRepository users;
    private final Clock clock;
    private final Set<String> origins;

    @Autowired
    public EmailAuthController(ObjectProvider<EmailLoginService> service, SessionAuthority sessions,
            UserRepository users, Clock clock,
            @Value("${users.google.http.allowed-origins:}") String allowedOrigins) {
        this(service, sessions, users, clock, Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet()));
    }

    public EmailAuthController(ObjectProvider<EmailLoginService> service, SessionAuthority sessions,
            UserRepository users, Clock clock, Set<String> origins) {
        this.service = service;
        this.sessions = sessions;
        this.users = users;
        this.clock = clock;
        // Fails closed on its own instead of relying on the Google adapter's startup check.
        if (origins == null || origins.isEmpty() || origins.stream().anyMatch(o -> !GoogleAuthController.validOrigin(o)))
            throw new IllegalArgumentException("Explicit HTTPS browser origins are required");
        this.origins = Set.copyOf(origins);
    }

    @ExceptionHandler({GoogleAuthController.AuthHttpException.class, SessionRejectedException.class,
            UserDomainException.class, EmailDeliveryException.class, IllegalArgumentException.class})
    public ResponseEntity<?> failed(Exception failure, HttpServletRequest request) {
        var response = ApiErrors.map(failure, request, clock);
        return withCors(response, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(HttpMessageNotReadableException ignored, HttpServletRequest request) {
        return failed(new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"), request);
    }

    @PostMapping(path = "/v1/auth/email/requests", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> request(@RequestBody JsonNode body, HttpServletRequest request) {
        var headers = guard(request);
        if (!onlyFields(body, "email") || !body.path("email").isTextual())
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        var login = available();
        Email email;
        try {
            email = new Email(body.get("email").asText());
        } catch (IllegalArgumentException invalid) {
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        String browser;
        try {
            browser = login.request(email);
        } catch (SessionRejectedException ineligibleDomain) {
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INSTITUTIONAL_EMAIL_REQUIRED");
        }
        // Identical for new, existing and throttled addresses; the cookie pins the link to this browser.
        headers.add(HttpHeaders.SET_COOKIE, cookie(ATTEMPT, browser, 600));
        return ResponseEntity.accepted().headers(headers)
                .body(Map.of("status", "accepted", "csrfToken", attemptCsrf(browser)));
    }

    @PostMapping(path = "/v1/auth/email/confirm", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> confirm(@RequestBody JsonNode body, HttpServletRequest request) throws ParseException {
        var headers = guard(request);
        if (!onlyFields(body, "attemptId", "token") || !body.path("attemptId").asText("").matches("[0-9a-f]{32}")
                || !body.path("token").asText("").matches("[0-9a-f]{64}"))
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        String browser = browserProof(request);
        var result = available().confirm(body.get("attemptId").asText(), browser, body.get("token").asText());
        headers.add(HttpHeaders.SET_COOKIE, cookie(ATTEMPT, "", 0));
        var session = sessions.current(result.sessionId()).orElseThrow(SessionRejectedException::new);
        var user = users.findById(new UserId(result.userId())).orElseThrow(SessionRejectedException::new);
        user.requireActive();
        long expiresAt = SignedJWT.parse(result.jwt()).getJWTClaimsSet().getExpirationTime().toInstant().getEpochSecond();
        headers.add(HttpHeaders.SET_COOKIE, cookie(REFRESH, result.refreshToken(),
                Math.max(0, session.deadline().getEpochSecond() - clock.instant().getEpochSecond())));
        var profile = user.profile();
        long deadline = session.deadline().getEpochSecond();
        var snapshot = new GoogleAuthController.SessionSnapshot(session.sessionId(), "active",
                clock.instant().getEpochSecond(), session.createdAt().getEpochSecond(),
                session.createdAt().getEpochSecond(), session.lastActivityAt().getEpochSecond(),
                session.renewableUntil().getEpochSecond(), session.idleUntil().getEpochSecond(),
                session.absoluteUntil().getEpochSecond(), deadline,
                Math.max(session.createdAt().getEpochSecond(), deadline - 300));
        return ResponseEntity.ok().headers(headers).body(new GoogleAuthController.AuthResponse(result.jwt(), "Bearer",
                expiresAt, new GoogleAuthController.AuthenticatedUser(result.userId(), profile.correo().address(),
                profile.details().nombre(), profile.fotoPerfil().toString()), snapshot, result.csrfToken()));
    }

    /** Same-browser proof: HttpOnly attempt cookie plus the CSRF value derived from it. */
    private static String browserProof(HttpServletRequest request) {
        String secret = null;
        if (request.getCookies() != null) for (var c : request.getCookies()) if (ATTEMPT.equals(c.getName())) {
            if (secret != null) secret = "";
            else secret = c.getValue();
        }
        String csrf = request.getHeader("X-CSRF-Token");
        if (secret == null || !secret.matches("[0-9a-f]{64}") || csrf == null
                || !java.security.MessageDigest.isEqual(attemptCsrf(secret).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        csrf.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new GoogleAuthController.AuthHttpException(HttpStatus.FORBIDDEN, "CSRF_INVALID");
        return secret;
    }

    private static String attemptCsrf(String secret) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("mtec-email-attempt-csrf:" + secret).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String cookie(String name, String value, long seconds) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(true).path("/").sameSite("Lax")
                .maxAge(seconds).build().toString();
    }

    private EmailLoginService available() {
        var login = service.getIfAvailable();
        if (login == null)
            throw new GoogleAuthController.AuthHttpException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE");
        return login;
    }

    private HttpHeaders guard(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        // The container's own TLS view; Forwarded/X-Forwarded-Proto from a client is never trusted here.
        if (!request.isSecure() || origin == null || !origins.contains(origin))
            throw new GoogleAuthController.AuthHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        if (!"web".equals(request.getHeader("X-MTEC-Client")))
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        return cors(origin);
    }

    private ResponseEntity<?> withCors(ResponseEntity<?> response, HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || !origins.contains(origin)) return response;
        var headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.putAll(cors(origin));
        return ResponseEntity.status(response.getStatusCode()).headers(headers).body(response.getBody());
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

    static boolean onlyFields(JsonNode body, String... required) {
        if (body == null || !body.isObject() || body.size() != required.length) return false;
        for (String name : required) if (!body.has(name) || !body.get(name).isTextual()) return false;
        return true;
    }
}

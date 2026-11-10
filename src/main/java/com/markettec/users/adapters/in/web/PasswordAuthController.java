package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.session.EmailDeliveryException;
import com.markettec.users.application.session.EmailLoginService;
import com.markettec.users.application.session.PasswordCredentialService;
import com.markettec.users.application.session.PasswordOperationException;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.domain.model.Email;
import com.markettec.users.domain.model.UserDomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.text.ParseException;
import java.time.Clock;
import java.util.Arrays;
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
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous password routes: sign-in with email and password, and the request of a recovery Magic Link.
 * Same controls as the Magic Link adapter: explicit HTTPS browser origin, the container's own TLS view,
 * the {@code X-MTEC-Client} marker, no-store responses and the same HttpOnly refresh cookie. A recovery
 * link is redeemed through the existing {@code POST /v1/auth/email/confirm}.
 */
@RestController
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.password", name = "enabled", havingValue = "true")
public final class PasswordAuthController {
    private final ObjectProvider<PasswordCredentialService> passwords;
    private final ObjectProvider<EmailLoginService> email;
    private final SessionAuthority sessions;
    private final UserRepository users;
    private final Clock clock;
    private final Set<String> origins;

    @Autowired
    public PasswordAuthController(ObjectProvider<PasswordCredentialService> passwords,
            ObjectProvider<EmailLoginService> email, SessionAuthority sessions, UserRepository users, Clock clock,
            @Value("${users.password.allowed-origins:${users.email.allowed-origins:${users.google.http.allowed-origins:}}}")
            String allowedOrigins) {
        this(passwords, email, sessions, users, clock, Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet()));
    }

    public PasswordAuthController(ObjectProvider<PasswordCredentialService> passwords,
            ObjectProvider<EmailLoginService> email, SessionAuthority sessions, UserRepository users, Clock clock,
            Set<String> origins) {
        this.passwords = passwords;
        this.email = email;
        this.sessions = sessions;
        this.users = users;
        this.clock = clock;
        if (origins == null || origins.isEmpty() || origins.stream().anyMatch(o -> !GoogleAuthController.validOrigin(o)))
            throw new IllegalArgumentException("Explicit HTTPS browser origins are required");
        this.origins = Set.copyOf(origins);
    }

    @ExceptionHandler({GoogleAuthController.AuthHttpException.class, SessionRejectedException.class,
            PasswordOperationException.class, UserDomainException.class, EmailDeliveryException.class,
            IllegalArgumentException.class})
    public ResponseEntity<?> failed(Exception failure, HttpServletRequest request) {
        var response = ApiErrors.map(failure, request, clock);
        String origin = request.getHeader("Origin");
        if (origin == null || !origins.contains(origin)) return response;
        var headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.putAll(EmailAuthController.cors(origin));
        return ResponseEntity.status(response.getStatusCode()).headers(headers).body(response.getBody());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(HttpMessageNotReadableException ignored, HttpServletRequest request) {
        return failed(new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"), request);
    }

    /** 200 {@code AuthResponse} + refresh cookie, exactly like Google and Magic Link sign-in. */
    @PostMapping(path = "/v1/auth/password/login", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(@RequestBody JsonNode body, HttpServletRequest request) throws ParseException {
        var headers = guard(request);
        if (!EmailAuthController.onlyFields(body, "email", "password"))
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        var service = passwords.getIfAvailable();
        if (service == null)
            throw new GoogleAuthController.AuthHttpException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE");
        var result = service.login(parse(body.get("email").asText()), body.get("password").asText());
        return EmailAuthController.authenticated(result, headers, sessions, users, clock);
    }

    /**
     * Acknowledged identically for known, unknown and throttled addresses (202 + browser-binding cookie);
     * a link is only sent for an existing institutional account.
     */
    @PostMapping(path = "/v1/auth/password/recovery/requests", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> recoveryRequest(@RequestBody JsonNode body, HttpServletRequest request) {
        var headers = guard(request);
        if (!EmailAuthController.onlyFields(body, "email"))
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        var login = email.getIfAvailable();
        if (login == null || !login.recoveryAvailable())
            throw new GoogleAuthController.AuthHttpException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE");
        String browser;
        try {
            browser = login.requestRecovery(parse(body.get("email").asText()));
        } catch (SessionRejectedException ineligibleDomain) {
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INSTITUTIONAL_EMAIL_REQUIRED");
        }
        headers.add(HttpHeaders.SET_COOKIE, EmailAuthController.cookie(EmailAuthController.ATTEMPT, browser, 600));
        return ResponseEntity.accepted().headers(headers)
                .body(Map.of("status", "accepted", "csrfToken", EmailAuthController.attemptCsrf(browser)));
    }

    private static Email parse(String address) {
        try {
            return new Email(address);
        } catch (IllegalArgumentException invalid) {
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
    }

    private HttpHeaders guard(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (!request.isSecure() || origin == null || !origins.contains(origin))
            throw new GoogleAuthController.AuthHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        if (!"web".equals(request.getHeader("X-MTEC-Client")))
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        return EmailAuthController.cors(origin);
    }
}

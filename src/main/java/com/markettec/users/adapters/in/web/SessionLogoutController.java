package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.session.logout", name = "enabled", havingValue = "true")
public final class SessionLogoutController {
    private static final String REFRESH = "__Host-mtec-refresh";
    private final SessionAuthority authority;
    private final Clock clock;
    private final Set<String> origins;
    private final ObjectMapper strictJson = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public SessionLogoutController(SessionAuthority authority, Clock clock,
            @Value("${users.session.http.allowed-origins:}") String allowedOrigins,
            @Value("${users.session.http.public-api-origin:}") String publicApiOrigin,
            @Value("${users.session.http.https-topology-verified:false}") boolean topologyVerified) {
        this.authority = authority;
        this.clock = clock;
        this.origins = Arrays.stream(allowedOrigins.split(",", -1)).map(String::trim)
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (!topologyVerified || Arrays.stream(allowedOrigins.split(",", -1)).anyMatch(String::isBlank)
                || !validOrigin(publicApiOrigin) || origins.isEmpty() || origins.stream().anyMatch(o -> !validOrigin(o)))
            throw new IllegalArgumentException("Explicit verified HTTPS session topology is required");
    }

    @PostMapping(path = "/v1/auth/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> logout(@RequestBody String body, HttpServletRequest request) {
        String origin = allowedOrigin(request);
        if (!request.isSecure()) throw new LogoutHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        Enumeration<String> clients = request.getHeaders("X-MTEC-Client");
        if (!clients.hasMoreElements() || !"web".equals(clients.nextElement()) || clients.hasMoreElements())
            throw new LogoutHttpException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        try {
            var json = strictJson.readTree(body);
            if (json == null || !json.isObject() || json.size() != 1
                    || !json.path("scope").isTextual() || !"current".equals(json.path("scope").asText()))
                throw new LogoutHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new LogoutHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        String refresh = refreshCookie(request);
        if (refresh != null) {
            Enumeration<String> proofs = request.getHeaders("X-CSRF-Token");
            String csrf = proofs.hasMoreElements() ? proofs.nextElement() : null;
            if (csrf == null || !csrf.matches("[0-9a-f]{64}") || proofs.hasMoreElements())
                throw new LogoutHttpException(HttpStatus.FORBIDDEN, "CSRF_INVALID");
            authority.logout(refresh, csrf);
        }
        return ResponseEntity.noContent().headers(cors(origin)).header(HttpHeaders.SET_COOKIE,
                ResponseCookie.from(REFRESH, "").secure(true).httpOnly(true).path("/")
                        .sameSite("Lax").maxAge(0).build().toString()).build();
    }

    public ResponseEntity<Void> preflight(HttpServletRequest request) {
        String origin = allowedOrigin(request);
        if (!request.isSecure() || !"POST".equals(request.getHeader("Access-Control-Request-Method")))
            throw new LogoutHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        String requested = request.getHeader("Access-Control-Request-Headers");
        if (requested == null || requested.isBlank() || Arrays.stream(requested.split(",", -1))
                .map(String::trim).map(String::toLowerCase)
                .anyMatch(h -> !Set.of("content-type", "x-mtec-client", "x-csrf-token", "x-correlation-id").contains(h)))
            throw new LogoutHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
        return ResponseEntity.noContent().headers(cors(origin))
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        "Content-Type, X-MTEC-Client, X-CSRF-Token, X-Correlation-ID").build();
    }

    @ExceptionHandler({LogoutHttpException.class, SessionRejectedException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<SessionBootstrapController.ErrorEnvelope> rejected(Exception rejected,
            HttpServletRequest request) {
        HttpStatus status = rejected instanceof LogoutHttpException failure ? failure.status
                : rejected instanceof HttpMessageNotReadableException ? HttpStatus.BAD_REQUEST : HttpStatus.UNAUTHORIZED;
        String code = rejected instanceof LogoutHttpException failure ? failure.code
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
            throw new LogoutHttpException(HttpStatus.FORBIDDEN, "ORIGIN_DENIED");
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
            if (seen) throw new LogoutHttpException(HttpStatus.UNAUTHORIZED, "REFRESH_INVALID");
            seen = true;
            value = cookie.getValue();
        }
        if (seen && (value == null || !value.matches("[0-9a-f]{64}")))
            throw new LogoutHttpException(HttpStatus.UNAUTHORIZED, "REFRESH_INVALID");
        return value;
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

    private static final class LogoutHttpException extends RuntimeException {
        final HttpStatus status;
        final String code;
        LogoutHttpException(HttpStatus status, String code) {
            super("Logout request rejected"); this.status = status; this.code = code;
        }
    }
}

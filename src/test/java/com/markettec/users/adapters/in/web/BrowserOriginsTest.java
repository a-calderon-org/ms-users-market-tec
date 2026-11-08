package com.markettec.users.adapters.in.web;

import com.markettec.users.adapters.out.jwt.InternalJwtService;
import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.session.EmailLoginService;
import com.markettec.users.application.session.GoogleLoginService;
import com.markettec.users.application.session.GoogleRegistrationService;
import com.markettec.users.application.session.SessionAuthority;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** P3: only the canonical serialized form of an HTTPS origin is configurable, in every adapter. */
class BrowserOriginsTest {
    private static final String GOOD = "https://app.markettec.example";

    static final List<String> NON_CANONICAL = List.of(
            // case and trailing-dot variants that evaded the localhost rule or never match a browser Origin
            "https://LOCALHOST", "https://Localhost", "https://localhost.", "https://localhost",
            "https://app.localhost", "https://APP.markettec.example", "https://app.markettec.example.",
            "HTTPS://app.markettec.example", "Https://app.markettec.example",
            // authority: empty port separator, ports, userinfo
            "https://app.markettec.example:", "https://app.markettec.example:443", "https://app.markettec.example:8443",
            "https://fixture@app.markettec.example", "https://u:p@app.markettec.example", "https://@app.markettec.example",
            // path, query, fragment (also empty ones)
            "https://app.markettec.example/", "https://app.markettec.example/x", "https://app.markettec.example?",
            "https://app.markettec.example?x=1", "https://app.markettec.example#", "https://app.markettec.example#x",
            // scheme, wildcard, malformed, reserved
            "http://app.markettec.example", "https://*.markettec.example", "*", "null", "", " https://app.markettec.example",
            "https://", "https://app..markettec.example", "https://-app.markettec.example", "https://app_x.markettec.example",
            "https://app.markettec.invalid", "https://app.markettec.example\\@evil.example",
            // IP literals, including loopback equivalents of localhost (A-12 needs a same-site host pair)
            "https://127.0.0.1", "https://127.1", "https://0.0.0.0", "https://10.0.0.5", "https://[::1]",
            "https://xn--caf-dma.example/", "https://café.example",
            // Codex reproductions: reserved name and hosts java.net.URI calls names but browsers parse as IPv4
            "https://invalid", "https://0x7f000001", "https://0xffffffff");

    /** Numeric hosts per the WHATWG "ends in a number" rule (hex, octal, shortened, mixed, out of range). */
    static final List<String> BROWSER_IPV4 = List.of(
            "https://0x7f000001", "https://0xffffffff", "https://0x7f.0x0.0x0.0x1", "https://0x", "https://0x0",
            "https://2130706433", "https://017700000001", "https://0177.0.0.1", "https://127.0.1", "https://127.1",
            "https://127.0.0x0.1", "https://0x7f.1", "https://4294967296", "https://99999999999999999999",
            "https://256.256.256.256", "https://app.0x10", "https://app.markettec.0x1f", "https://app.markettec.123");

    static final List<String> CANONICAL = List.of(GOOD, "https://api.markettec.example",
            "https://markettec.example", "https://xn--caf-dma.example", "https://a-b.c-d.example", "https://intranet",
            // near misses that browsers also treat as domains: numeric or 0x non-final labels. (A final label
            // starting with a digit, e.g. app.0xfoo, is rejected conservatively by java.net.URI; no TLD does.)
            "https://2024.markettec.example", "https://0x7f.markettec.example",
            "https://invalid-app.example", "https://app.invalidx");

    @Test void canonicalOriginsAreAccepted() {
        for (String origin : CANONICAL) assertTrue(BrowserOrigins.isCanonicalHttpsOrigin(origin), origin);
    }

    @Test void nonCanonicalOrExcludedOriginsAreRejectedWithoutResolvingNames() {
        for (String origin : NON_CANONICAL) assertFalse(BrowserOrigins.isCanonicalHttpsOrigin(origin), origin);
        assertFalse(BrowserOrigins.isCanonicalHttpsOrigin(null));
        assertFalse(BrowserOrigins.isCanonicalHttpsOrigin("https://" + "a".repeat(260) + ".example"));
    }

    @Test void hostsABrowserWouldParseAsIpv4AreRejected() {
        for (String origin : BROWSER_IPV4) assertFalse(BrowserOrigins.isCanonicalHttpsOrigin(origin), origin);
    }

    @SuppressWarnings("unchecked")
    @Test void everyAdapterRejectsTheVariantsForAllowListsAndPublicApiOrigins() {
        var sessions = mock(SessionAuthority.class);
        var clock = Clock.systemUTC();
        ObjectProvider<EmailLoginService> email = mock(ObjectProvider.class);
        var users = mock(UserRepository.class);
        var jwt = mock(InternalJwtService.class);
        var login = mock(GoogleLoginService.class);
        var registration = mock(GoogleRegistrationService.class);
        List<Consumer<String>> allowLists = List.of(
                o -> new GoogleAuthController(login, registration, sessions, users, clock, Set.of(o)),
                o -> new EmailAuthController(email, sessions, users, clock, Set.of(o)),
                o -> new ProtectedApiCorsFilter(Set.of(o)),
                o -> new SessionBootstrapController(sessions, clock, o, GOOD, true),
                o -> new SessionRefreshController(sessions, jwt, clock, o, GOOD, true),
                o -> new SessionLogoutController(sessions, clock, o, GOOD, true));
        List<Consumer<String>> publicApi = List.of(
                o -> new GoogleAuthController(login, registration, sessions, users, clock, GOOD, o, true),
                o -> new SessionBootstrapController(sessions, clock, GOOD, o, true),
                o -> new SessionRefreshController(sessions, jwt, clock, GOOD, o, true),
                o -> new SessionLogoutController(sessions, clock, GOOD, o, true));
        for (var adapter : allowLists) {
            assertDoesNotThrow(() -> adapter.accept(GOOD));
            // Comma-separated allow-lists are trimmed per entry, so surrounding blanks are list syntax.
            for (String bad : java.util.stream.Stream.concat(NON_CANONICAL.stream(), BROWSER_IPV4.stream())
                    .filter(s -> !s.isBlank() && s.equals(s.trim())).toList())
                assertThrows(IllegalArgumentException.class, () -> adapter.accept(bad), bad);
        }
        for (var adapter : publicApi) {
            assertDoesNotThrow(() -> adapter.accept(GOOD));
            for (String bad : java.util.stream.Stream.concat(NON_CANONICAL.stream(), BROWSER_IPV4.stream()).toList())
                assertThrows(IllegalArgumentException.class, () -> adapter.accept(bad), bad);
        }
    }
}

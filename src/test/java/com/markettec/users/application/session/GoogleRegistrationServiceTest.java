package com.markettec.users.application.session;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.application.ports.out.*;
import com.markettec.users.domain.model.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GoogleRegistrationServiceTest {
    private final Instant start = Instant.parse("2026-11-03T12:00:00Z");
    private final MutableClock clock = new MutableClock(start);
    private final InMemoryUserStore users = new InMemoryUserStore();
    private final Map<String, GoogleLoginIntent> intents = new HashMap<>();
    private final ExternalIdentityKey key = new ExternalIdentityKey("https://accounts.google.com", "new-google-sub");
    private Email email = new Email("someone@tec.ac.cr");
    private boolean verified = true, historical, invalidToken;
    private String subject = "new-google-sub";
    private UserId lastGenerated;
    private final SessionAuthority sessions = mock(SessionAuthority.class);
    private final UserTransactions tx = new UserTransactions() {
        public synchronized <T> T execute(Supplier<T> work) {
            var before = new HashMap<>(intents);
            try { return users.execute(work); }
            catch (RuntimeException | Error failure) {
                intents.clear(); intents.putAll(before);
                throw failure;
            }
        }
    };
    private final GoogleLoginIntentRepository intentRepo = new GoogleLoginIntentRepository() {
        public void insert(GoogleLoginIntent a) { intents.put(a.attemptId(), a); }
        public Optional<GoogleLoginIntent> loadLocked(String id) { return Optional.ofNullable(intents.get(id)); }
        public void replace(GoogleLoginIntent before, GoogleLoginIntent after) {
            if (!intents.replace(before.attemptId(), before, after)) throw new IllegalStateException("race");
        }
    };
    private final PendingGoogleRegistrationRepository pendingRepo = new PendingGoogleRegistrationRepository() {
        public void insert(PendingGoogleRegistration ignored) { fail("No pending Google registration"); }
        public Optional<PendingGoogleRegistration> loadLocked(String id) { fail("No pending Google registration"); return Optional.empty(); }
        public void replace(PendingGoogleRegistration before, PendingGoogleRegistration after) { fail("No pending Google registration"); }
        public boolean historicalEmailExists(String ignored) { return historical; }
    };
    private final GoogleIdentityVerifier verifier = (token, context) -> {
        if (invalidToken || !("nonce:" + context.expectedNonce()).equals(token))
            throw new IllegalArgumentException("invalid token");
        return new VerifiedGoogleIdentity(key.issuer(), subject, context.expectedAudience(),
                start, start.plusSeconds(3600), verified ? email : null);
    };
    private GoogleRegistrationService service() {
        when(sessions.createGoogle(any())).thenReturn(new AuthenticatedSession("a".repeat(32)));
        doAnswer(call -> new SessionTokens("jwt", "b".repeat(64), "c".repeat(64),
                "a".repeat(32), lastGenerated.value(), start.plusSeconds(1800)))
                .when(sessions).issue(any(), any());
        return new GoogleRegistrationService(intentRepo, pendingRepo, tx, verifier, users, users,
                () -> { lastGenerated = new UuidUserIdGenerator().nextId(); return lastGenerated; },
                sessions, ignored -> "jwt", "client-id", clock, new SecureRandom());
    }
    private GoogleLoginService.Result complete(GoogleRegistrationService service, GoogleLoginService.Challenge c) {
        return service.complete(c.attemptId(), c.browserSecret(), "nonce:" + c.nonce());
    }

    @Test void verifiedInstitutionalGoogleCreatesAccountLinkAndCredentialsImmediately() {
        var service = service();
        var c = service.begin();
        var result = complete(service, c);
        assertEquals(lastGenerated.value(), result.userId());
        assertEquals("jwt", result.jwt());
        assertEquals(64, result.refreshToken().length());
        assertEquals(64, result.csrfToken().length());
        assertTrue(users.findById(lastGenerated).orElseThrow().profile().verificado());
        assertEquals(lastGenerated, users.findByKey(key).orElseThrow().userId());
        assertTrue(intents.get(c.attemptId()).consumed());
        verify(sessions).issue(any(), any());
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED,
                assertThrows(SessionRejectedException.class, () -> complete(service, c)).reason());
    }

    @Test void onlyExactInstitutionalDomainsAreEligible() {
        for (String address : List.of("a@estudiantec.cr", "a@itcr.ac.cr", "a@tec.ac.cr",
                "a@x.tec.ac.cr", "a@tec.ac.cr.evil", "a@example.com")) {
            email = new Email(address);
            subject = address;
            var service = service();
            var c = service.begin();
            boolean eligible = Set.of("a@estudiantec.cr", "a@itcr.ac.cr", "a@tec.ac.cr").contains(address);
            if (eligible) {
                assertNotNull(complete(service, c));
                assertTrue(intents.get(c.attemptId()).consumed());
            } else {
                assertThrows(SessionRejectedException.class, () -> complete(service, c));
                assertFalse(intents.get(c.attemptId()).consumed());
            }
        }
    }

    @Test void unverifiedOrMissingEmailCannotRegister() {
        var service = service();
        var c = service.begin();
        verified = false;
        assertThrows(SessionRejectedException.class, () -> complete(service, c));
        assertTrue(users.findByKey(key).isEmpty());
        assertFalse(intents.get(c.attemptId()).consumed());
    }

    @Test void existingEmailOrSubjectCannotLinkAutomatically() {
        var service = service();
        var c = service.begin();
        historical = true;
        assertEquals(SessionRejectedException.Reason.IDENTITY_CONFLICT,
                assertThrows(SessionRejectedException.class, () -> complete(service, c)).reason());
        historical = false;
        var owner = User.create(new UuidUserIdGenerator().nextId(), email,
                new ProfileDetails("owner", "Owner", ""), java.net.URI.create("about:blank"), start);
        users.insert(owner);
        users.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, key, owner.userId(), email));
        assertEquals(SessionRejectedException.Reason.IDENTITY_CONFLICT,
                assertThrows(SessionRejectedException.class, () -> complete(service, c)).reason());
        assertFalse(intents.get(c.attemptId()).consumed());
        verify(sessions, never()).issue(any(), any());
    }

    @Test void invalidProofBrowserAndExpiryCannotCreateAccount() {
        var service = service();
        var c = service.begin();
        assertThrows(SessionRejectedException.class, () ->
                service.complete(c.attemptId(), "f".repeat(64), "nonce:" + c.nonce()));
        invalidToken = true;
        assertThrows(IllegalArgumentException.class, () -> complete(service, c));
        invalidToken = false;
        clock.set(start.plusSeconds(300));
        assertEquals(SessionRejectedException.Reason.ATTEMPT_EXPIRED,
                assertThrows(SessionRejectedException.class, () -> complete(service, c)).reason());
        assertTrue(users.findByKey(key).isEmpty());
    }

    @Test void signingAndSessionFailureRollBackAccountLinkAndAttempt() {
        var service = service();
        var c = service.begin();
        doThrow(new IllegalStateException("session failed")).when(sessions).createGoogle(any());
        assertThrows(IllegalStateException.class, () -> complete(service, c));
        assertTrue(users.findByKey(key).isEmpty());
        assertTrue(users.findById(lastGenerated).isEmpty());
        assertFalse(intents.get(c.attemptId()).consumed());
        reset(sessions);
        var retry = service();
        doThrow(new IllegalStateException("JWT failed")).when(sessions).issue(any(), any());
        assertThrows(IllegalStateException.class, () -> complete(retry, c));
        assertTrue(users.findByKey(key).isEmpty());
        assertFalse(intents.get(c.attemptId()).consumed());
    }

    @Test void persistenceFailureRollsBackUserAndAttempt() {
        var c = service().begin();
        UserRepository failing = new UserRepository() {
            public Optional<User> findById(UserId id) { return users.findById(id); }
            public void insert(User user) {
                users.insert(user);
                throw new IllegalStateException("persistence failed");
            }
            public void replace(User before, User after) { users.replace(before, after); }
        };
        var broken = new GoogleRegistrationService(intentRepo, pendingRepo, tx, verifier, failing, users,
                new UuidUserIdGenerator(), sessions, ignored -> "jwt", "client-id", clock, new SecureRandom());
        assertThrows(IllegalStateException.class, () -> complete(broken, c));
        assertTrue(users.findByKey(key).isEmpty());
        assertFalse(intents.get(c.attemptId()).consumed());
        verify(sessions, never()).issue(any(), any());
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        void set(Instant value) { now = value; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}

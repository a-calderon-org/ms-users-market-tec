package com.markettec.users.application.session;
import com.markettec.users.application.session.CsrfKeys;

import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.application.ports.out.GoogleIdentityVerifier;
import com.markettec.users.application.ports.out.VerifiedGoogleIdentity;
import com.markettec.users.application.ports.out.UserTransactions;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoogleLoginServiceTest {
    private final Instant start = Instant.parse("2026-11-03T12:00:00Z");
    private final MutableClock clock = new MutableClock(start);
    private final InMemoryUserStore users = new InMemoryUserStore();
    private final Map<String, GoogleLoginIntent> intents = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, SessionVerifiers> credentials = new HashMap<>();
    private final Object lock = new Object();
    private final ExternalIdentityKey key = new ExternalIdentityKey("https://accounts.google.com", "google-sub");
    private boolean failSession;
    private boolean failJwt;
    private String tokenNonce;
    private boolean invalidToken;

    private final UserTransactions tx = new UserTransactions() {
        public <T> T execute(Supplier<T> work) {
            synchronized (lock) {
                var oldIntents = new HashMap<>(intents);
                var oldSessions = new HashMap<>(sessions);
                var oldCredentials = new HashMap<>(credentials);
                try { return users.execute(work); }
                catch (RuntimeException | Error ex) {
                    intents.clear(); intents.putAll(oldIntents);
                    sessions.clear(); sessions.putAll(oldSessions);
                    credentials.clear(); credentials.putAll(oldCredentials);
                    throw ex;
                }
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
    private final SessionRepository sessionRepo = new SessionRepository() {
        public void insert(Session s) {
            if (failSession) throw new IllegalStateException("persist failed");
            sessions.put(s.sessionId(), s);
        }
        public Optional<Session> loadLocked(String id) { return Optional.ofNullable(sessions.get(id)); }
        public Optional<Session> loadByRefreshHashLocked(String hash) {
            return credentials.entrySet().stream().filter(e -> e.getValue().refreshHash().equals(hash))
                    .map(e -> sessions.get(e.getKey())).findFirst();
        }
        public void replace(Session before, Session after) { sessions.put(before.sessionId(), after); }
        public Optional<SessionVerifiers> verifiers(String id) { return Optional.ofNullable(credentials.get(id)); }
        public void initializeVerifiers(String id, SessionVerifiers value) {
            if (credentials.putIfAbsent(id, value) != null) throw new IllegalStateException("duplicate");
        }
        public Optional<ConsumedCredential> consumedByRefreshHashLocked(String hash) { return Optional.empty(); }
        public Optional<ConsumedCredential> closedByRefreshHashLocked(String hash) { return Optional.empty(); }
        public void rotateVerifiers(String id, SessionVerifiers old, SessionVerifiers next, Instant consumedAt) {
            if (!credentials.replace(id, old, next)) throw new IllegalStateException("stale");
        }
    };
    private final GoogleIdentityVerifier verifier = (token, context) -> {
        if (invalidToken || !"valid".equals(token) || !Objects.equals(tokenNonce, context.expectedNonce())
                || !"client-id".equals(context.expectedAudience())) throw new IllegalArgumentException("invalid Google token");
        return new VerifiedGoogleIdentity(key.issuer(), key.subject(), context.expectedAudience(), start, start.plusSeconds(3600));
    };

    private GoogleLoginService service() {
        var authority = new SessionAuthority(sessionRepo, users, users, tx, clock, new SecureRandom(),
                new SessionActionAuthorization() {
                    public void requireRenewal(Session s) { throw new SessionRejectedException(); }
                    public void requireAdmissibleActivity(Session s) { throw new SessionRejectedException(); }
                }, CsrfKeys.fromJson("{\"test\":\"" + java.util.Base64.getEncoder().encodeToString(new byte[32]) + "\"}", "test"));
        return new GoogleLoginService(intentRepo, tx, verifier, authority,
                authenticated -> { if (failJwt) throw new IllegalStateException("signing failed"); return "internal-jwt"; },
                "client-id", clock, new SecureRandom());
    }

    private User link() {
        var email = new Email("one@example.invalid");
        User user = User.create(new UuidUserIdGenerator().nextId(), email,
                new ProfileDetails("one", "One", "User"), URI.create("https://example.invalid/p"), start);
        users.insert(user);
        users.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, key, user.userId(), email));
        return user;
    }

    @Test void linkedGoogleIdentityCreatesOneSessionAndInternalIdentity() {
        User user = link();
        var challenge = service().begin(); tokenNonce = challenge.nonce();
        var result = service().complete(challenge.attemptId(), challenge.browserSecret(), "valid");
        assertEquals(user.userId().value(), result.userId());
        assertEquals("internal-jwt", result.jwt());
        assertEquals(64, result.refreshToken().length());
        assertEquals(64, result.csrfToken().length());
        assertEquals(1, credentials.size());
        assertFalse(result.toString().contains(result.refreshToken()));
        assertEquals(start.plusSeconds(1800), result.sessionDeadline());
        assertEquals(1, sessions.size());
        assertTrue(intents.get(challenge.attemptId()).consumed());
        assertThrows(SessionRejectedException.class,
                () -> service().complete(challenge.attemptId(), challenge.browserSecret(), "valid"));
    }

    @Test void blockedAccountCannotCreateSession() {
        User user = link();
        users.replace(user, user.deactivate());
        var challenge = service().begin(); tokenNonce = challenge.nonce();
        assertThrows(UserDomainException.class,
                () -> service().complete(challenge.attemptId(), challenge.browserSecret(), "valid"));
        assertTrue(sessions.isEmpty());
    }

    @Test void inactiveLinksCannotCreateSession() {
        link();
        for (IdentityLinkStatus status : List.of(IdentityLinkStatus.DISABLED, IdentityLinkStatus.REVOKED,
                IdentityLinkStatus.PENDING, IdentityLinkStatus.CONFLICT)) {
            users.replaceStatus(users.findByKey(key).orElseThrow(), status);
            var challenge = service().begin(); tokenNonce = challenge.nonce();
            assertThrows(SessionRejectedException.class,
                    () -> service().complete(challenge.attemptId(), challenge.browserSecret(), "valid"));
            assertTrue(sessions.isEmpty());
            assertFalse(intents.get(challenge.attemptId()).consumed());
            users.replaceStatus(users.findByKey(key).orElseThrow(), IdentityLinkStatus.ACTIVE);
        }
    }

    @Test void providerConflictCannotCreateSession() {
        var email = new Email("one@example.invalid");
        User user = User.create(new UuidUserIdGenerator().nextId(), email,
                new ProfileDetails("one", "One", "User"), URI.create("https://example.invalid/p"), start);
        users.insert(user);
        users.insertOrGet(new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL, key, user.userId(), email));
        var challenge = service().begin(); tokenNonce = challenge.nonce();
        assertThrows(SessionRejectedException.class,
                () -> service().complete(challenge.attemptId(), challenge.browserSecret(), "valid"));
        assertTrue(sessions.isEmpty());
    }

    @Test void wrongContextNonceExpiryAndInvalidTokenNeverCreateSession() {
        link();
        var first = service().begin(); tokenNonce = first.nonce();
        assertThrows(SessionRejectedException.class, () -> service().complete(first.attemptId(), "0".repeat(64), "valid"));
        tokenNonce = "1".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> service().complete(first.attemptId(), first.browserSecret(), "valid"));
        tokenNonce = first.nonce(); invalidToken = true;
        assertThrows(IllegalArgumentException.class,
                () -> service().complete(first.attemptId(), first.browserSecret(), "valid"));
        assertTrue(sessions.isEmpty());
        assertFalse(intents.get(first.attemptId()).consumed());
        invalidToken = false; clock.set(start.plusSeconds(300));
        assertThrows(SessionRejectedException.class,
                () -> service().complete(first.attemptId(), first.browserSecret(), "valid"));
        assertTrue(sessions.isEmpty());
    }

    @Test void unlinkedIdentityAndSameEmailAccountCannotLoginOrMerge() {
        var email = new Email("same@example.invalid");
        users.insert(User.create(new UuidUserIdGenerator().nextId(), email,
                new ProfileDetails("same", "Same", "User"), URI.create("https://example.invalid/p"), start));
        var challenge = service().begin(); tokenNonce = challenge.nonce();
        assertThrows(SessionRejectedException.class,
                () -> service().complete(challenge.attemptId(), challenge.browserSecret(), "valid"));
        assertTrue(sessions.isEmpty());
        assertTrue(users.findByKey(key).isEmpty());
    }

    @Test void persistenceFailureRollsBackConsumptionAndSession() {
        link(); var challenge = service().begin(); tokenNonce = challenge.nonce(); failSession = true;
        assertThrows(IllegalStateException.class,
                () -> service().complete(challenge.attemptId(), challenge.browserSecret(), "valid"));
        assertFalse(intents.get(challenge.attemptId()).consumed());
        assertTrue(sessions.isEmpty());
        failSession = false;
        service().complete(challenge.attemptId(), challenge.browserSecret(), "valid");
        assertEquals(1, sessions.size());
    }

    @Test void signingFailureRollsBackGoogleLoginAndCredentialIssuance() {
        link(); var challenge = service().begin(); tokenNonce = challenge.nonce(); failJwt = true;
        assertThrows(IllegalStateException.class,
                () -> service().complete(challenge.attemptId(), challenge.browserSecret(), "valid"));
        assertFalse(intents.get(challenge.attemptId()).consumed());
        assertTrue(sessions.isEmpty());
        assertTrue(credentials.isEmpty());
        failJwt = false;
        service().complete(challenge.attemptId(), challenge.browserSecret(), "valid");
    }

    @Test void concurrentExchangeHasOneWinner() throws Exception {
        link(); var challenge = service().begin(); tokenNonce = challenge.nonce();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<Boolean> call = () -> {
                gate.await();
                try { service().complete(challenge.attemptId(), challenge.browserSecret(), "valid"); return true; }
                catch (SessionRejectedException ignored) { return false; }
            };
            var a = pool.submit(call); var b = pool.submit(call); gate.countDown();
            assertEquals(1, (a.get(5, TimeUnit.SECONDS) ? 1 : 0) + (b.get(5, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, sessions.size());
        } finally { pool.shutdownNow(); }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void set(Instant value) { instant = value; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant; }
    }
}

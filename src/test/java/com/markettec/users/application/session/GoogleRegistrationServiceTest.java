package com.markettec.users.application.session;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.application.ports.out.*;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GoogleRegistrationServiceTest {
    private final Instant start = Instant.parse("2026-11-03T12:00:00Z");
    private final MutableClock clock = new MutableClock(start);
    private final InMemoryUserStore users = new InMemoryUserStore();
    private final Map<String, GoogleLoginIntent> intents = new HashMap<>();
    private final Map<String, Session> sessionRows = new HashMap<>();
    private final Map<String, SessionVerifiers> credentials = new HashMap<>();
    private final ExternalIdentityKey key = new ExternalIdentityKey("https://accounts.google.com", "new-google-sub");
    private final Email email = new Email("someone@tec.ac.cr");
    private boolean invalidToken, failLink, failSession, failJwt;
    private String expectedNonce;
    private UserId lastGenerated;
    private final UserTransactions tx = new UserTransactions() {
        public synchronized <T> T execute(Supplier<T> work) {
            var oldIntents = new HashMap<>(intents);
            var oldSessions = new HashMap<>(sessionRows);
            var oldCredentials = new HashMap<>(credentials);
            try { return users.execute(work); }
            catch (RuntimeException | Error ex) {
                intents.clear(); intents.putAll(oldIntents);
                sessionRows.clear(); sessionRows.putAll(oldSessions);
                credentials.clear(); credentials.putAll(oldCredentials);
                throw ex;
            }
        }
    };
    private final GoogleLoginIntentRepository attemptRepo = new GoogleLoginIntentRepository() {
        public void insert(GoogleLoginIntent intent) { intents.put(intent.attemptId(), intent); }
        public Optional<GoogleLoginIntent> loadLocked(String id) { return Optional.ofNullable(intents.get(id)); }
        public void replace(GoogleLoginIntent before, GoogleLoginIntent after) {
            if (!intents.replace(before.attemptId(), before, after)) throw new IllegalStateException("race");
        }
    };
    private final ExternalIdentityRepository links = new ExternalIdentityRepository() {
        public Optional<ExternalIdentity> findByKey(ExternalIdentityKey key) { return users.findByKey(key); }
        public ExternalIdentity insertOrGet(ExternalIdentity link) {
            if (failLink) throw new IllegalStateException("link failed");
            return users.insertOrGet(link);
        }
        public void replaceStatus(ExternalIdentity before, IdentityLinkStatus after) { users.replaceStatus(before, after); }
    };
    private final SessionRepository sessionRepo = new SessionRepository() {
        public void insert(Session session) {
            if (failSession) throw new IllegalStateException("session failed");
            sessionRows.put(session.sessionId(), session);
        }
        public Optional<Session> loadLocked(String id) { return Optional.ofNullable(sessionRows.get(id)); }
        public Optional<Session> loadByRefreshHashLocked(String hash) {
            return credentials.entrySet().stream().filter(e -> e.getValue().refreshHash().equals(hash))
                    .map(e -> sessionRows.get(e.getKey())).findFirst();
        }
        public void replace(Session before, Session after) { sessionRows.put(before.sessionId(), after); }
        public Optional<SessionVerifiers> verifiers(String id) { return Optional.ofNullable(credentials.get(id)); }
        public void initializeVerifiers(String id, SessionVerifiers value) {
            if (credentials.putIfAbsent(id, value) != null) throw new IllegalStateException("duplicate");
        }
        public Optional<ConsumedCredential> consumedByRefreshHashLocked(String hash) { return Optional.empty(); }
        public void rotateVerifiers(String id, SessionVerifiers old, SessionVerifiers next, Instant consumedAt) {
            if (!credentials.replace(id, old, next)) throw new IllegalStateException("stale");
        }
    };
    private final GoogleIdentityVerifier verifier = (token, context) -> {
        if (invalidToken || !(("valid".equals(token) && Objects.equals(expectedNonce, context.expectedNonce()))
                || ("nonce:" + context.expectedNonce()).equals(token)))
            throw new IllegalArgumentException("invalid token");
        return new VerifiedGoogleIdentity(key.issuer(), key.subject(), context.expectedAudience(),
                start, start.plusSeconds(3600), email);
    };
    private SessionAuthority authority() {
        return new SessionAuthority(sessionRepo, links, users, tx, clock, new SecureRandom(),
                new SessionActionAuthorization() {
                    public void requireRenewal(Session s) { throw new SessionRejectedException(); }
                    public void requireAdmissibleActivity(Session s) { throw new SessionRejectedException(); }
                });
    }
    private GoogleRegistrationService service() {
        return new GoogleRegistrationService(attemptRepo, tx, verifier, users, links,
                () -> { lastGenerated = new UuidUserIdGenerator().nextId(); return lastGenerated; },
                authority(), s -> { if (failJwt) throw new IllegalStateException("JWT failed"); return "jwt"; },
                "client-id", clock, new SecureRandom());
    }
    private GoogleLoginService.Challenge begin() {
        var challenge = service().begin(); expectedNonce = challenge.nonce(); return challenge;
    }
    private void complete(GoogleLoginService.Challenge c) {
        service().complete(c.attemptId(), c.browserSecret(), "valid");
    }
    private User existing(IdentityLinkStatus status) {
        User user = User.create(new UuidUserIdGenerator().nextId(), email,
                new ProfileDetails("old", "Old", "Account"), URI.create("about:blank"), start);
        users.insert(user);
        users.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, key, user.userId(), email, status));
        return user;
    }

    @Test void newRegistrationCreatesIsolatedBasicAccountAndOneActiveLink() {
        var c = begin(); var result = service().complete(c.attemptId(), c.browserSecret(), "valid");
        User user = users.findById(new UserId(result.userId())).orElseThrow();
        assertNotEquals(key.subject(), result.userId());
        assertEquals(AccountStatus.ACTIVE, user.status());
        assertFalse(user.profile().verificado());
        assertEquals("jwt", result.jwt());
        assertEquals(64, result.refreshToken().length());
        assertEquals(64, result.csrfToken().length());
        assertEquals(1, credentials.size());
        assertEquals(IdentityLinkStatus.ACTIVE, users.findByKey(key).orElseThrow().status());
        assertEquals(result.userId(), users.findByKey(key).orElseThrow().userId().value());
        assertTrue(intents.get(c.attemptId()).consumed());
        assertEquals(1, sessionRows.size());
        assertThrows(SessionRejectedException.class, () -> complete(c));
    }

    @Test void invalidTokenNonceBrowserAndExpiryRejectWithoutAccount() {
        var c = begin();
        assertThrows(SessionRejectedException.class, () -> service().complete(c.attemptId(), "0".repeat(64), "valid"));
        expectedNonce = "f".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> complete(c));
        expectedNonce = c.nonce(); invalidToken = true;
        assertThrows(IllegalArgumentException.class, () -> complete(c));
        invalidToken = false; clock.set(start.plusSeconds(300));
        assertThrows(SessionRejectedException.class, () -> complete(c));
        assertTrue(users.findByKey(key).isEmpty());
        assertFalse(intents.get(c.attemptId()).consumed());
    }

    @Test void loginIntentCannotRegister() {
        var c = begin();
        var a = intents.get(c.attemptId());
        intents.put(c.attemptId(), new GoogleLoginIntent(a.attemptId(), a.nonce(), a.browserHash(),
                a.createdAt(), a.expiresAt(), false, 0));
        assertThrows(SessionRejectedException.class, () -> complete(c));
        assertTrue(users.findByKey(key).isEmpty());
    }

    @Test void allHistoricalLinkStatesRejectWithoutMutation() {
        var user = existing(IdentityLinkStatus.ACTIVE);
        for (var status : IdentityLinkStatus.values()) {
            users.replaceStatus(users.findByKey(key).orElseThrow(), status);
            var c = begin();
            assertThrows(SessionRejectedException.class, () -> complete(c));
            assertEquals(status, users.findByKey(key).orElseThrow().status());
            assertEquals(user.userId(), users.findByKey(key).orElseThrow().userId());
            assertFalse(intents.get(c.attemptId()).consumed());
        }
    }

    @Test void sameEmailNeverTransfersExistingAccount() {
        User old = User.create(new UuidUserIdGenerator().nextId(), email,
                new ProfileDetails("old", "Old", "Account"), URI.create("about:blank"), start);
        users.insert(old);
        var c = begin(); var result = service().complete(c.attemptId(), c.browserSecret(), "valid");
        assertNotEquals(old.userId().value(), result.userId());
        assertEquals(old, users.findById(old.userId()).orElseThrow());
    }

    @Test void linkFailureRollsBackAccountAndAttempt() {
        var c = begin(); failLink = true;
        assertThrows(IllegalStateException.class, () -> complete(c));
        assertTrue(users.findByKey(key).isEmpty());
        assertTrue(users.findById(lastGenerated).isEmpty());
        assertFalse(intents.get(c.attemptId()).consumed());
        failLink = false; complete(c);
        assertTrue(intents.get(c.attemptId()).consumed());
    }

    @Test void sessionFailureAfterCommitDoesNotPermitDuplicateRegistration() {
        var c = begin(); failSession = true;
        assertThrows(IllegalStateException.class, () -> complete(c));
        assertTrue(intents.get(c.attemptId()).consumed());
        assertTrue(users.findByKey(key).isPresent());
        failSession = false;
        assertThrows(SessionRejectedException.class, () -> complete(c));
        assertTrue(sessionRows.isEmpty());
    }

    @Test void signingFailureAfterRegistrationLeavesNoHalfUsableSession() {
        var c = begin(); failJwt = true;
        assertThrows(IllegalStateException.class, () -> complete(c));
        assertTrue(intents.get(c.attemptId()).consumed());
        assertTrue(users.findByKey(key).isPresent());
        assertTrue(sessionRows.isEmpty());
        assertTrue(credentials.isEmpty());
        failJwt = false;
        assertThrows(SessionRejectedException.class, () -> complete(c));
    }

    @Test void accountInsertFailureRollsBackIntent() {
        var c = begin();
        UserRepository broken = new UserRepository() {
            public Optional<User> findById(UserId id) { return users.findById(id); }
            public void insert(User user) { throw new IllegalStateException("account failed"); }
            public void replace(User before, User after) { users.replace(before, after); }
        };
        var failing = new GoogleRegistrationService(attemptRepo, tx, verifier, broken, links,
                new UuidUserIdGenerator(), authority(), s -> "jwt", "client-id", clock, new SecureRandom());
        assertThrows(IllegalStateException.class,
                () -> failing.complete(c.attemptId(), c.browserSecret(), "valid"));
        assertFalse(intents.get(c.attemptId()).consumed());
        assertTrue(users.findByKey(key).isEmpty());
    }

    @Test void reusedNonceFromAnotherAttemptIsRejected() {
        var first = begin(); var second = begin();
        assertThrows(IllegalArgumentException.class,
                () -> service().complete(second.attemptId(), second.browserSecret(), "nonce:" + first.nonce()));
        assertFalse(intents.get(second.attemptId()).consumed());
        assertTrue(users.findByKey(key).isEmpty());
    }

    @Test void simultaneousConsumptionOfOneAttemptHasOneWinner() throws Exception {
        var c = begin();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<Boolean> call = () -> {
                gate.await();
                try {
                    service().complete(c.attemptId(), c.browserSecret(), "nonce:" + c.nonce());
                    return true;
                } catch (SessionRejectedException rejected) { return false; }
            };
            var one = pool.submit(call); var two = pool.submit(call); gate.countDown();
            assertEquals(1, (one.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (two.get(5, TimeUnit.SECONDS) ? 1 : 0));
            assertTrue(intents.get(c.attemptId()).consumed());
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentRegistrationOfSameIdentityHasOneWinner() throws Exception {
        var a = begin(); var b = begin();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<Boolean> first = () -> { gate.await();
                try { service().complete(a.attemptId(), a.browserSecret(), "nonce:" + a.nonce()); return true; }
                catch (SessionRejectedException ignored) { return false; } };
            Callable<Boolean> second = () -> { gate.await();
                try { service().complete(b.attemptId(), b.browserSecret(), "nonce:" + b.nonce()); return true; }
                catch (SessionRejectedException ignored) { return false; } };
            var x = pool.submit(first); var y = pool.submit(second); gate.countDown();
            assertEquals(1, (x.get(5, TimeUnit.SECONDS) ? 1 : 0) + (y.get(5, TimeUnit.SECONDS) ? 1 : 0));
            assertTrue(users.findByKey(key).isPresent());
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

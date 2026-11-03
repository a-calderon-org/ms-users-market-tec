package com.markettec.users.application.session;

import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.application.ports.out.UserTransactions;
import com.markettec.users.domain.model.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SessionAuthorityTest {
    private final Instant start = Instant.parse("2026-11-03T12:00:00Z");
    private final MutableClock clock = new MutableClock(start);
    private final InMemoryUserStore users = new InMemoryUserStore();
    private final MemorySessions sessions = new MemorySessions();
    private final VerifiedExternalIdentity evidence = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
            new ExternalIdentityKey("https://accounts.google.com", "subject"), new Email("a@estudiantec.cr"));
    private final SessionActionAuthorization allow = new SessionActionAuthorization() {
        public void requireRenewal(Session s) { }
        public void requireAdmissibleActivity(Session s) { }
    };

    private SessionAuthority authority(SessionActionAuthorization authorization) {
        UserTransactions atomic = new UserTransactions() {
            public <T> T execute(java.util.function.Supplier<T> work) {
                synchronized (sessions) {
                    var oldData = new HashMap<>(sessions.data);
                    var oldCredentials = new HashMap<>(sessions.credentials);
                    try { return users.execute(work); }
                    catch (RuntimeException | Error failure) {
                        sessions.data.clear(); sessions.data.putAll(oldData);
                        sessions.credentials.clear(); sessions.credentials.putAll(oldCredentials);
                        throw failure;
                    }
                }
            }
        };
        return new SessionAuthority(sessions, users, users, atomic, clock, new SecureRandom(), authorization);
    }

    private Session create() {
        User user = User.create(new UuidUserIdGenerator().nextId(), evidence.email(),
                new ProfileDetails("ana", "Ana", "López"), URI.create("https://example.invalid/a"), start);
        users.insert(user);
        users.insertOrGet(new ExternalIdentity(evidence.provider(), evidence.key(), user.userId(), evidence.email()));
        return authority(allow).create(evidence);
    }

    @Test void createsOpaquePersistedSessionFromLinkedVerifiedIdentity() {
        Session s = create();
        assertEquals(32, s.sessionId().length());
        assertEquals(s, sessions.loadLocked(s.sessionId()).orElseThrow());
        assertEquals(start.plus(Duration.ofMinutes(60)), s.renewableUntil());
        assertEquals(start.plus(Duration.ofMinutes(30)), s.idleUntil());
        assertEquals(start.plus(Duration.ofHours(8)), s.absoluteUntil());
        assertTrue(authority(allow).current(s.sessionId()).isPresent());
        assertEquals(0, sessions.loadLocked(s.sessionId()).orElseThrow().version());
    }

    @Test void authenticatedIssuanceContextRequiresVerifiedLinkedIdentity() {
        Session s = create();
        AuthenticatedSession context = authority(allow).authenticate(evidence, s.sessionId());
        assertEquals(s.sessionId(), context.sessionId());
        VerifiedExternalIdentity stranger = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", "stranger"), evidence.email());
        assertThrows(SessionRejectedException.class,
                () -> authority(allow).authenticate(stranger, s.sessionId()));
        assertThrows(SessionRejectedException.class,
                () -> authority(allow).authenticate(evidence, "00000000000000000000000000000000"));
        var link = users.findByKey(evidence.key()).orElseThrow();
        users.replaceStatus(link, IdentityLinkStatus.DISABLED);
        assertThrows(SessionRejectedException.class, () -> authority(allow).createAuthenticated(evidence));
        assertThrows(SessionRejectedException.class, () -> authority(allow).authenticate(evidence, s.sessionId()));
        users.replaceStatus(users.findByKey(evidence.key()).orElseThrow(), IdentityLinkStatus.ACTIVE);
        authority(allow).revoke(s.sessionId());
        assertThrows(SessionRejectedException.class,
                () -> authority(allow).authenticate(evidence, s.sessionId()));
    }

    @Test void verifiedLoginCanMintIssuanceContextWithoutHttpIds() {
        Session s = create();
        AuthenticatedSession context = authority(allow).createAuthenticated(evidence);
        assertNotEquals(s.sessionId(), context.sessionId());
        assertTrue(authority(allow).current(context.sessionId()).isPresent());
    }

    @Test void missingOrUnlinkedEvidenceFailsClosed() {
        assertThrows(SessionRejectedException.class, () -> authority(allow).create(evidence));
        assertTrue(authority(allow).current("00000000000000000000000000000000").isEmpty());
    }

    @Test void firstDeadlineExpiresWithoutLeewayOrImplicitRenewal() {
        Session s = create();
        clock.set(start.plus(Duration.ofMinutes(30)));
        assertTrue(authority(allow).current(s.sessionId()).isEmpty());
        assertThrows(SessionRejectedException.class, () -> authority(allow).renew(s.sessionId()));
        assertFalse(sessions.loadLocked(s.sessionId()).orElseThrow().revoked());
        assertEquals(0, sessions.loadLocked(s.sessionId()).orElseThrow().version());
    }

    @Test void activityDoesNotRenewWindowAndWindowExpiresAtSixtyMinutes() {
        Session s = create();
        clock.set(start.plus(Duration.ofMinutes(29)));
        authority(allow).recordAdmissibleActivity(s.sessionId());
        clock.set(start.plus(Duration.ofMinutes(58)));
        authority(allow).recordAdmissibleActivity(s.sessionId());
        clock.set(start.plus(Duration.ofMinutes(60)));
        assertTrue(authority(allow).current(s.sessionId()).isEmpty());
    }

    @Test void explicitRenewalCannotExtendAbsoluteDeadline() {
        Session s = create();
        for (int minute = 29; minute < 480; minute += 29) {
            clock.set(start.plus(Duration.ofMinutes(minute)));
            authority(allow).recordAdmissibleActivity(s.sessionId());
            authority(allow).renew(s.sessionId());
        }
        clock.set(start.plus(Duration.ofHours(8)));
        assertTrue(authority(allow).current(s.sessionId()).isEmpty());
        assertThrows(SessionRejectedException.class, () -> authority(allow).recordAdmissibleActivity(s.sessionId()));
    }

    @Test void revocationIsIdempotentAndNeverRevives() {
        Session s = create();
        authority(allow).revoke(s.sessionId());
        authority(allow).revoke(s.sessionId());
        assertTrue(authority(allow).current(s.sessionId()).isEmpty());
        assertThrows(SessionRejectedException.class, () -> authority(allow).renew(s.sessionId()));
        assertEquals(1, sessions.loadLocked(s.sessionId()).orElseThrow().version());
    }

    @Test void authorizationAndStoreFailureFailClosed() {
        Session s = create();
        SessionActionAuthorization deny = new SessionActionAuthorization() {
            public void requireRenewal(Session x) { throw new SessionRejectedException(); }
            public void requireAdmissibleActivity(Session x) { throw new SessionRejectedException(); }
        };
        assertThrows(SessionRejectedException.class, () -> authority(deny).renew(s.sessionId()));
        assertThrows(SessionRejectedException.class, () -> authority(deny).recordAdmissibleActivity(s.sessionId()));
        sessions.fail = true;
        assertThrows(IllegalStateException.class, () -> authority(allow).current(s.sessionId()));
        assertThrows(IllegalStateException.class, () -> authority(allow).revoke(s.sessionId()));
    }

    @Test void concurrentRevocationWinsOverSubsequentRenewalAndActivity() throws Exception {
        Session s = create();
        var pool = Executors.newFixedThreadPool(3);
        try {
            var gate = new CountDownLatch(1);
            var tasks = List.<Callable<Void>>of(
                    () -> { gate.await(); authority(allow).revoke(s.sessionId()); return null; },
                    () -> { gate.await(); try { authority(allow).renew(s.sessionId()); } catch (SessionRejectedException ignored) { } return null; },
                    () -> { gate.await(); try { authority(allow).recordAdmissibleActivity(s.sessionId()); } catch (SessionRejectedException ignored) { } return null; });
            var futures = tasks.stream().map(pool::submit).toList();
            gate.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertTrue(authority(allow).current(s.sessionId()).isEmpty());
            assertTrue(sessions.loadLocked(s.sessionId()).orElseThrow().revoked());
        } finally { pool.shutdownNow(); }
    }

    @Test void simultaneousRevocationsAreIdempotent() throws Exception {
        Session s = create();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            var first = pool.submit(() -> { gate.await(); authority(allow).revoke(s.sessionId()); return null; });
            var second = pool.submit(() -> { gate.await(); authority(allow).revoke(s.sessionId()); return null; });
            gate.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, sessions.loadLocked(s.sessionId()).orElseThrow().version());
        } finally { pool.shutdownNow(); }
    }

    @Test void issuesOpaqueCredentialsAndSessionBoundCsrfWithoutPersistingSecrets() {
        create();
        var first = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        var second = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt2");
        assertEquals(64, first.refreshToken().length());
        assertNotEquals(first.refreshToken(), second.refreshToken());
        assertNotEquals(first.csrfToken(), second.csrfToken());
        assertFalse(sessions.verifiers(first.sessionId()).orElseThrow().toString().contains(first.refreshToken()));
        assertFalse(sessions.verifiers(first.sessionId()).orElseThrow().toString().contains(first.csrfToken()));
        assertFalse(first.toString().contains(first.refreshToken()));
        authority(allow).requireCsrf(first.sessionId(), new UserId(first.userId()), first.csrfToken());
        assertThrows(SessionRejectedException.class, () -> authority(allow).requireCsrf(first.sessionId(),
                new UuidUserIdGenerator().nextId(), first.csrfToken()));
        assertThrows(SessionRejectedException.class, () -> authority(allow).requireCsrf(first.sessionId(),
                new UserId(first.userId()), second.csrfToken()));
        assertThrows(SessionRejectedException.class, () -> authority(allow).requireCsrf(first.sessionId(),
                new UserId(first.userId()), null));
        assertThrows(SessionRejectedException.class, () -> authority(allow).requireCsrf(first.sessionId(),
                new UserId(first.userId()), "0".repeat(64)));
    }

    @Test void refreshRotatesBothCredentialsAndRejectsReplayOrCrossSession() {
        create();
        var first = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        var other = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        var idleBefore = sessions.loadLocked(first.sessionId()).orElseThrow().idleUntil();
        var next = authority(allow).refresh(first.refreshToken(), first.csrfToken(), a -> "next-jwt");
        assertEquals("next-jwt", next.jwt());
        assertEquals(first.sessionId(), next.sessionId());
        assertNotEquals(first.refreshToken(), next.refreshToken());
        assertNotEquals(first.csrfToken(), next.csrfToken());
        assertEquals(idleBefore, sessions.loadLocked(first.sessionId()).orElseThrow().idleUntil());
        assertThrows(SessionRejectedException.class, () -> authority(allow).refresh(first.refreshToken(), first.csrfToken(), a -> "jwt"));
        assertThrows(SessionRejectedException.class, () -> authority(allow).refresh(next.refreshToken(), other.csrfToken(), a -> "jwt"));
        assertThrows(SessionRejectedException.class, () -> authority(allow).requireCsrf(first.sessionId(),
                new UserId(first.userId()), first.csrfToken()));
        authority(allow).requireCsrf(first.sessionId(), new UserId(first.userId()), next.csrfToken());
        assertFalse(sessions.loadLocked(first.sessionId()).orElseThrow().revoked());
    }

    @Test void technicalRefreshRotatesCredentialsWithoutRenewalOrActivity() {
        create();
        SessionActionAuthorization deny = new SessionActionAuthorization() {
            public void requireRenewal(Session s) { throw new SessionRejectedException(); }
            public void requireAdmissibleActivity(Session s) { throw new SessionRejectedException(); }
        };
        var first = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        var before = sessions.loadLocked(first.sessionId()).orElseThrow();
        clock.set(start.plus(Duration.ofMinutes(29)));
        var rotated = authority(deny).refresh(first.refreshToken(), first.csrfToken(), a -> "rotated-jwt");
        assertEquals("rotated-jwt", rotated.jwt());
        assertNotEquals(first.refreshToken(), rotated.refreshToken());
        assertNotEquals(first.csrfToken(), rotated.csrfToken());
        assertEquals(before, sessions.loadLocked(first.sessionId()).orElseThrow());
        assertEquals(before.deadline(), rotated.sessionDeadline());
        clock.set(start.plus(Duration.ofMinutes(30)));
        assertThrows(SessionRejectedException.class,
                () -> authority(deny).refresh(rotated.refreshToken(), rotated.csrfToken(), a -> "jwt"));
    }

    @Test void concurrentRefreshAllowsExactlyOneWinner() throws Exception {
        create();
        var first = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<Boolean> action = () -> {
                gate.await();
                try { authority(allow).refresh(first.refreshToken(), first.csrfToken(), a -> "jwt"); return true; }
                catch (SessionRejectedException ignored) { return false; }
            };
            var a = pool.submit(action); var b = pool.submit(action);
            gate.countDown();
            assertNotEquals(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    @Test void refreshRollbackRestoresPreviousCredentialAndSession() {
        create();
        var first = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        var before = sessions.loadLocked(first.sessionId()).orElseThrow();
        assertThrows(IllegalStateException.class, () -> authority(allow).refresh(first.refreshToken(), first.csrfToken(),
                a -> { throw new IllegalStateException("signing failed"); }));
        assertEquals(before, sessions.loadLocked(first.sessionId()).orElseThrow());
        authority(allow).refresh(first.refreshToken(), first.csrfToken(), a -> "jwt");
    }

    @Test void logoutAndRevocationDestroyCredentialsWithoutRevivingExpiry() {
        create();
        var first = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        authority(allow).logout(first.refreshToken(), first.csrfToken());
        assertTrue(sessions.verifiers(first.sessionId()).isEmpty());
        assertThrows(SessionRejectedException.class, () -> authority(allow).refresh(first.refreshToken(), first.csrfToken(), a -> "jwt"));
        assertThrows(SessionRejectedException.class, () -> authority(allow).requireCsrf(first.sessionId(),
                new UserId(first.userId()), first.csrfToken()));
        var second = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        authority(allow).revoke(second.sessionId());
        assertTrue(sessions.verifiers(second.sessionId()).isEmpty());
        clock.set(start.plusSeconds(1800));
        assertThrows(SessionRejectedException.class, () -> authority(allow).refresh(second.refreshToken(), second.csrfToken(), a -> "jwt"));
    }

    @Test void refreshHonorsIdleWindowAndAbsoluteDeadlines() {
        create();
        var idle = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        clock.set(start.plusSeconds(1800));
        assertThrows(SessionRejectedException.class, () -> authority(allow).refresh(idle.refreshToken(), idle.csrfToken(), a -> "jwt"));

        clock.set(start);
        var window = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        clock.set(start.plusSeconds(29 * 60));
        authority(allow).recordAdmissibleActivity(window.sessionId());
        clock.set(start.plusSeconds(58 * 60));
        authority(allow).recordAdmissibleActivity(window.sessionId());
        clock.set(start.plusSeconds(60 * 60));
        assertThrows(SessionRejectedException.class, () -> authority(allow).refresh(window.refreshToken(), window.csrfToken(), a -> "jwt"));

        clock.set(start);
        var active = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        for (int minute = 29; minute < 480; minute += 29) {
            clock.set(start.plusSeconds(minute * 60L));
            authority(allow).recordAdmissibleActivity(active.sessionId());
            authority(allow).renew(active.sessionId());
            active = authority(allow).refresh(active.refreshToken(), active.csrfToken(), a -> "jwt");
            assertFalse(active.sessionDeadline().isAfter(start.plusSeconds(8 * 3600)));
        }
        var last = active;
        clock.set(start.plusSeconds(8 * 3600));
        assertThrows(SessionRejectedException.class, () -> authority(allow).refresh(last.refreshToken(), last.csrfToken(), a -> "jwt"));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void set(Instant value) { instant = value; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return instant; }
    }

    private static final class MemorySessions implements SessionRepository {
        private final Map<String, Session> data = new HashMap<>();
        private final Map<String, SessionVerifiers> credentials = new HashMap<>();
        volatile boolean fail;
        public synchronized void insert(Session s) { check(); data.put(s.sessionId(), s); }
        public synchronized Optional<Session> loadLocked(String id) { check(); return Optional.ofNullable(data.get(id)); }
        public synchronized Optional<Session> loadByRefreshHashLocked(String hash) {
            check();
            return credentials.entrySet().stream().filter(e -> e.getValue().refreshHash().equals(hash))
                    .map(e -> data.get(e.getKey())).findFirst();
        }
        public synchronized void replace(Session old, Session next) {
            check();
            if (!data.replace(old.sessionId(), old, next)) throw new IllegalStateException("stale");
            if (next.revoked()) credentials.remove(old.sessionId());
        }
        public synchronized Optional<SessionVerifiers> verifiers(String id) { check(); return Optional.ofNullable(credentials.get(id)); }
        public synchronized void initializeVerifiers(String id, SessionVerifiers value) {
            check();
            if (credentials.putIfAbsent(id, value) != null) throw new IllegalStateException("duplicate");
        }
        public synchronized void rotateVerifiers(String id, SessionVerifiers old, SessionVerifiers next) {
            check();
            if (!credentials.replace(id, old, next)) throw new IllegalStateException("stale");
        }
        private void check() { if (fail) throw new IllegalStateException("store unavailable"); }
    }
}

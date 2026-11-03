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
                synchronized (sessions) { return users.execute(work); }
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
        volatile boolean fail;
        public synchronized void insert(Session s) { check(); data.put(s.sessionId(), s); }
        public synchronized Optional<Session> loadLocked(String id) { check(); return Optional.ofNullable(data.get(id)); }
        public synchronized void replace(Session old, Session next) {
            check();
            if (!data.replace(old.sessionId(), old, next)) throw new IllegalStateException("stale");
        }
        private void check() { if (fail) throw new IllegalStateException("store unavailable"); }
    }
}

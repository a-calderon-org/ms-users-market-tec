package com.markettec.users.application.session;

import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.jwt.InternalJwtService;
import com.markettec.users.adapters.in.web.SessionBootstrapController;
import com.markettec.users.adapters.in.web.SessionRefreshController;
import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.application.ports.out.UserTransactions;
import com.markettec.users.domain.model.*;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.Cookie;

import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
                    var oldConsumed = new HashMap<>(sessions.consumed);
                    var oldClosed = new HashMap<>(sessions.closed);
                    var oldGeneration = new HashMap<>(sessions.generation);
                    try { return users.execute(work); }
                    catch (RuntimeException | Error failure) {
                        sessions.data.clear(); sessions.data.putAll(oldData);
                        sessions.credentials.clear(); sessions.credentials.putAll(oldCredentials);
                        sessions.consumed.clear(); sessions.consumed.putAll(oldConsumed);
                        sessions.closed.clear(); sessions.closed.putAll(oldClosed);
                        sessions.generation.clear(); sessions.generation.putAll(oldGeneration);
                        throw failure;
                    }
                }
            }
        };
        return new SessionAuthority(sessions, users, users, atomic, clock, new SecureRandom(), authorization, testKeys());
    }

    private static CsrfKeys testKeys() {
        return CsrfKeys.fromJson("{\"test\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\"}", "test");
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
        assertEquals(start, s.requireRenewedAt());
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

    @Test void explicitRenewalRecordsInstantEvenWhenAbsoluteCapKeepsDeadline() {
        Session s = create();
        for (int minute = 29; minute <= 435; minute += 29) {
            clock.set(start.plus(Duration.ofMinutes(minute)));
            authority(allow).recordAdmissibleActivity(s.sessionId());
            authority(allow).renew(s.sessionId());
        }
        Session first = sessions.loadLocked(s.sessionId()).orElseThrow();
        assertEquals(start.plus(Duration.ofMinutes(435)), first.requireRenewedAt());
        assertEquals(first.absoluteUntil(), first.renewableUntil());
        clock.set(start.plus(Duration.ofMinutes(450)));
        Session second = authority(allow).renew(s.sessionId());
        assertEquals(first.renewableUntil(), second.renewableUntil());
        assertEquals(start.plus(Duration.ofMinutes(450)), second.requireRenewedAt());
        assertEquals(first.version() + 1, second.version());
        authority(allow).revoke(s.sessionId());
        assertEquals(second.requireRenewedAt(), sessions.loadLocked(s.sessionId()).orElseThrow().requireRenewedAt());
    }

    @Test void historicalUnknownRenewalRequiresExplicitRenewalToBecomeKnown() {
        Session s = create();
        Session historical = new Session(s.sessionId(), s.userId(), s.createdAt(), null,
                s.renewableUntil(), s.lastActivityAt(), s.idleUntil(), s.absoluteUntil(), false, 1);
        assertThrows(SessionRejectedException.class, historical::requireRenewedAt);
        assertNull(historical.activityAt(start.plusSeconds(1)).renewedAt());
        assertNull(historical.revoke().renewedAt());
        assertEquals(start.plusSeconds(1), historical.renewAt(start.plusSeconds(1)).requireRenewedAt());
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
        assertEquals(1L, sessions.generation.get(first.sessionId()));
        assertEquals(1, sessions.consumed.size());
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED,
                assertThrows(SessionRejectedException.class, () -> authority(allow).refresh(
                        first.refreshToken(), first.csrfToken(), a -> "jwt")).reason());
        assertEquals(SessionRejectedException.Reason.PROOF_INVALID,
                assertThrows(SessionRejectedException.class, () -> authority(allow).refresh(
                        first.refreshToken(), other.csrfToken(), a -> "jwt")).reason());
        assertEquals(SessionRejectedException.Reason.PROOF_INVALID,
                assertThrows(SessionRejectedException.class, () -> authority(allow).refresh(
                        "0".repeat(64), first.csrfToken(), a -> "jwt")).reason());
        assertThrows(SessionRejectedException.class, () -> authority(allow).refresh(next.refreshToken(), other.csrfToken(), a -> "jwt"));
        assertEquals(1, sessions.consumed.size());
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
            assertEquals(1, sessions.consumed.size());
            assertEquals(1L, sessions.generation.get(first.sessionId()));
        } finally { pool.shutdownNow(); }
    }

    @Test void refreshRollbackRestoresPreviousCredentialAndSession() {
        create();
        var first = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        var before = sessions.loadLocked(first.sessionId()).orElseThrow();
        assertThrows(IllegalStateException.class, () -> authority(allow).refresh(first.refreshToken(), first.csrfToken(),
                a -> { throw new IllegalStateException("signing failed"); }));
        assertEquals(before, sessions.loadLocked(first.sessionId()).orElseThrow());
        assertTrue(sessions.consumed.isEmpty());
        sessions.failAfterConsumption = true;
        assertThrows(IllegalStateException.class, () -> authority(allow).refresh(
                first.refreshToken(), first.csrfToken(), a -> "jwt"));
        assertTrue(sessions.consumed.isEmpty());
        assertEquals(0L, sessions.generation.get(first.sessionId()));
        sessions.failAfterConsumption = false;
        authority(allow).refresh(first.refreshToken(), first.csrfToken(), a -> "jwt");
    }

    @Test void httpRefreshReturnsCoherentSnapshotWithoutChangingSessionState() {
        create();
        var authority = authority(allow);
        var first = authority.issue(authority.createAuthenticated(evidence), a -> "initial");
        var before = sessions.loadLocked(first.sessionId()).orElseThrow();
        var result = authority.refreshWithSnapshot(first.refreshToken(), first.csrfToken(), a -> "signed");
        assertEquals(before, result.session());
        assertEquals(before, sessions.loadLocked(first.sessionId()).orElseThrow());
        assertEquals(before.userId(), result.user().userId());
        assertEquals("signed", result.tokens().jwt());
        assertNotEquals(first.refreshToken(), result.tokens().refreshToken());
        assertNotEquals(first.csrfToken(), result.tokens().csrfToken());
        assertThrows(SessionRejectedException.class,
                () -> authority.refreshWithSnapshot(first.refreshToken(), first.csrfToken(), a -> "bad"));
        assertEquals(before, sessions.loadLocked(first.sessionId()).orElseThrow());
        assertEquals("again", authority.refreshWithSnapshot(result.tokens().refreshToken(),
                result.tokens().csrfToken(), a -> "again").tokens().jwt());
    }

    @Test void httpRefreshRollsBackOnSigningFailureAndRejectsInactiveAccount() {
        create();
        var authority = authority(allow);
        var first = authority.issue(authority.createAuthenticated(evidence), a -> "initial");
        assertThrows(IllegalStateException.class,
                () -> authority.refreshWithSnapshot(first.refreshToken(), first.csrfToken(),
                        a -> { throw new IllegalStateException("signing failed"); }));
        assertTrue(sessions.consumed.isEmpty());
        assertEquals(0L, sessions.generation.get(first.sessionId()));
        var inactive = users.findById(sessions.loadLocked(first.sessionId()).orElseThrow().userId())
                .orElseThrow().deactivate();
        var blockedUsers = org.mockito.Mockito.mock(
                com.markettec.users.application.ports.out.UserRepository.class);
        org.mockito.Mockito.when(blockedUsers.findById(inactive.userId())).thenReturn(Optional.of(inactive));
        var blocked = new SessionAuthority(sessions, users, blockedUsers, users,
                clock, new SecureRandom(), allow, testKeys());
        assertThrows(UserDomainException.class,
                () -> blocked.refreshWithSnapshot(first.refreshToken(), first.csrfToken(), a -> "bad"));
        assertTrue(sessions.consumed.isEmpty());
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

    @Test void logoutIsIdempotentOnlyForExactLastProofAndDoesNotAffectOtherSessions() {
        create();
        var auth = authority(allow);
        var first = auth.issue(auth.createAuthenticated(evidence), a -> "jwt");
        var other = auth.issue(auth.createAuthenticated(evidence), a -> "jwt");
        Session before = sessions.loadLocked(first.sessionId()).orElseThrow();
        auth.logout(first.refreshToken(), first.csrfToken());
        Session closed = sessions.loadLocked(first.sessionId()).orElseThrow();
        assertEquals(before.version() + 1, closed.version());
        assertEquals(before.deadline(), closed.deadline());
        assertEquals(before.lastActivityAt(), closed.lastActivityAt());
        assertEquals(before.renewedAt(), closed.renewedAt());
        auth.logout(first.refreshToken(), first.csrfToken());
        assertEquals(closed, sessions.loadLocked(first.sessionId()).orElseThrow());
        assertTrue(sessions.loadLocked(other.sessionId()).orElseThrow().validAt(start));
        assertThrows(SessionRejectedException.class, () -> auth.logout(first.refreshToken(), other.csrfToken()));
        assertThrows(SessionRejectedException.class, () -> auth.logout("f".repeat(64), first.csrfToken()));
    }

    @Test void expiredCurrentProofAllowsCleanupWithoutMutation() {
        create();
        var auth = authority(allow);
        var issued = auth.issue(auth.createAuthenticated(evidence), a -> "jwt");
        Session before = sessions.loadLocked(issued.sessionId()).orElseThrow();
        clock.set(before.deadline());
        auth.logout(issued.refreshToken(), issued.csrfToken());
        assertEquals(before, sessions.loadLocked(issued.sessionId()).orElseThrow());
        assertThrows(SessionRejectedException.class, () -> auth.logout(issued.refreshToken(), "f".repeat(64)));
    }

    @Test void consumedRefreshCannotRevokeWinningGeneration() {
        create();
        var auth = authority(allow);
        var old = auth.issue(auth.createAuthenticated(evidence), a -> "jwt");
        var winner = auth.refresh(old.refreshToken(), old.csrfToken(), a -> "jwt");
        assertThrows(SessionRejectedException.class, () -> auth.logout(old.refreshToken(), old.csrfToken()));
        assertTrue(sessions.loadLocked(winner.sessionId()).orElseThrow().validAt(start));
        auth.logout(winner.refreshToken(), winner.csrfToken());
        assertThrows(SessionRejectedException.class, () -> auth.logout(old.refreshToken(), old.csrfToken()));
    }

    @Test void inactiveOwnerCanStillDestroyPresentedSession() {
        create();
        var auth = authority(allow);
        var issued = auth.issue(auth.createAuthenticated(evidence), a -> "jwt");
        var owner = users.findById(new UserId(issued.userId())).orElseThrow();
        users.replace(owner, owner.deactivate());
        auth.logout(issued.refreshToken(), issued.csrfToken());
        assertTrue(sessions.loadLocked(issued.sessionId()).orElseThrow().revoked());
    }

    @Test void simultaneousLogoutOnlyChangesVersionOnce() throws Exception {
        create();
        var auth = authority(allow);
        var issued = auth.issue(auth.createAuthenticated(evidence), a -> "jwt");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<Void> action = () -> {
                gate.await(); auth.logout(issued.refreshToken(), issued.csrfToken()); return null;
            };
            var a = pool.submit(action); var b = pool.submit(action);
            gate.countDown();
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
            assertEquals(1, sessions.loadLocked(issued.sessionId()).orElseThrow().version());
        } finally { pool.shutdownNow(); }
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

    @Test void recoversCurrentCsrfWithoutChangingSessionState() {
        create();
        var authority = authority(allow);
        var first = authority.issue(authority.createAuthenticated(evidence), a -> "jwt");
        var before = sessions.loadLocked(first.sessionId()).orElseThrow();
        assertEquals(first.csrfToken(), authority.recoverCsrf(first.refreshToken()));
        authority.requireCsrf(first.sessionId(), before.userId(), authority.recoverCsrf(first.refreshToken()));
        assertEquals(before, sessions.loadLocked(first.sessionId()).orElseThrow());
        var next = authority.refresh(first.refreshToken(), first.csrfToken(), a -> "jwt");
        assertNotEquals(first.csrfToken(), next.csrfToken());
        assertThrows(SessionRejectedException.class, () -> authority.recoverCsrf(first.refreshToken()));
        assertEquals(next.csrfToken(), authority.recoverCsrf(next.refreshToken()));
        assertEquals(before, sessions.loadLocked(first.sessionId()).orElseThrow());
        authority.revoke(next.sessionId());
        assertThrows(SessionRejectedException.class, () -> authority.recoverCsrf(next.refreshToken()));
    }

    @Test void recoveryRejectsWrongAndExpiredEvidence() {
        create();
        var authority = authority(allow);
        var issued = authority.issue(authority.createAuthenticated(evidence), a -> "jwt");
        assertThrows(SessionRejectedException.class, () -> authority.recoverCsrf("0".repeat(64)));
        assertThrows(SessionRejectedException.class, () -> authority.requireCsrf(issued.sessionId(),
                sessions.loadLocked(issued.sessionId()).orElseThrow().userId(), "0".repeat(64)));
        clock.set(start.plus(Duration.ofMinutes(30)));
        assertThrows(SessionRejectedException.class, () -> authority.recoverCsrf(issued.refreshToken()));
    }

    @Test void rotatedKeyRemainsAvailableOnlyWhileConfigured() {
        create();
        var old = authority(allow);
        var issued = old.issue(old.createAuthenticated(evidence), a -> "jwt");
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        String second = Base64.getEncoder().encodeToString(new byte[48]);
        var both = CsrfKeys.fromJson("{\"test\":\"" + key + "\",\"next\":\"" + second + "\"}", "next");
        var rotated = new SessionAuthority(sessions, users, users, new UserTransactions() { public <T> T execute(java.util.function.Supplier<T> work) { return users.execute(work); } }, clock,
                new SecureRandom(), allow, both);
        assertEquals(issued.csrfToken(), rotated.recoverCsrf(issued.refreshToken()));
        var next = rotated.refresh(issued.refreshToken(), issued.csrfToken(), a -> "jwt");
        assertEquals(next.csrfToken(), rotated.recoverCsrf(next.refreshToken()));
        assertThrows(SessionRejectedException.class, () -> old.recoverCsrf(next.refreshToken()));
    }

    @Test void bootstrapReturnsCurrentCsrfAndPersistedRenewalWithoutMutation() {
        create();
        var authority = authority(allow);
        var issued = authority.issue(authority.createAuthenticated(evidence), a -> "jwt");
        Session before = sessions.loadLocked(issued.sessionId()).orElseThrow();
        var result = authority.bootstrap(issued.refreshToken());
        assertEquals(before, result.session());
        assertEquals(before.userId(), result.user().userId());
        assertEquals(issued.csrfToken(), result.csrfToken());
        assertEquals(start, result.serverTime());
        assertEquals(before, sessions.loadLocked(issued.sessionId()).orElseThrow());
        assertEquals(before.requireRenewedAt(), result.session().requireRenewedAt());
        clock.set(start.plusSeconds(60));
        Session renewed = authority.renew(issued.sessionId());
        var later = authority.bootstrap(issued.refreshToken());
        assertEquals(start.plusSeconds(60), later.session().requireRenewedAt());
        assertEquals(renewed, later.session());
        assertEquals(renewed, sessions.loadLocked(issued.sessionId()).orElseThrow());
    }

    @Test void bootstrapRejectsConsumedUnknownExpiredAndRevokedCredentials() {
        create();
        var authority = authority(allow);
        var issued = authority.issue(authority.createAuthenticated(evidence), a -> "jwt");
        assertThrows(SessionRejectedException.class, () -> authority.bootstrap("0".repeat(64)));
        var rotated = authority.refresh(issued.refreshToken(), issued.csrfToken(), a -> "jwt");
        assertThrows(SessionRejectedException.class, () -> authority.bootstrap(issued.refreshToken()));
        assertEquals(rotated.csrfToken(), authority.bootstrap(rotated.refreshToken()).csrfToken());
        authority.revoke(rotated.sessionId());
        assertThrows(SessionRejectedException.class, () -> authority.bootstrap(rotated.refreshToken()));
        var next = authority.issue(authority.createAuthenticated(evidence), a -> "jwt");
        clock.set(start.plusSeconds(1800));
        assertThrows(SessionRejectedException.class, () -> authority.bootstrap(next.refreshToken()));
    }

    @Test void bootstrapRejectsInactiveAccount() {
        create();
        var issued = authority(allow).issue(authority(allow).createAuthenticated(evidence), a -> "jwt");
        var inactive = users.findById(sessions.loadLocked(issued.sessionId()).orElseThrow().userId())
                .orElseThrow().deactivate();
        var blockedUsers = org.mockito.Mockito.mock(
                com.markettec.users.application.ports.out.UserRepository.class);
        org.mockito.Mockito.when(blockedUsers.findById(inactive.userId())).thenReturn(Optional.of(inactive));
        UserTransactions readOnly = new UserTransactions() {
            public <T> T execute(java.util.function.Supplier<T> work) {
                synchronized (sessions) { return work.get(); }
            }
        };
        var blocked = new SessionAuthority(sessions, users, blockedUsers, readOnly,
                clock, new SecureRandom(), allow, testKeys());
        assertThrows(UserDomainException.class, () -> blocked.bootstrap(issued.refreshToken()));
    }

    @Test void bootstrapSerializesWithRefreshAndRevocation() throws Exception {
        create();
        var authority = authority(allow);
        var issued = authority.issue(authority.createAuthenticated(evidence), a -> "jwt");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);
        try {
            Future<Optional<SessionAuthority.SessionBootstrap>> reader = pool.submit(() -> {
                startLine.await();
                try { return Optional.of(authority.bootstrap(issued.refreshToken())); }
                catch (SessionRejectedException rejected) { return Optional.empty(); }
            });
            Future<SessionTokens> writer = pool.submit(() -> {
                startLine.await();
                return authority.refresh(issued.refreshToken(), issued.csrfToken(), a -> "next-jwt");
            });
            startLine.countDown();
            var observed = reader.get(5, TimeUnit.SECONDS);
            var rotated = writer.get(5, TimeUnit.SECONDS);
            observed.ifPresent(snapshot -> assertEquals(issued.csrfToken(), snapshot.csrfToken()));
            assertThrows(SessionRejectedException.class, () -> authority.bootstrap(issued.refreshToken()));
            assertEquals(rotated.csrfToken(), authority.bootstrap(rotated.refreshToken()).csrfToken());
            authority.revoke(rotated.sessionId());
            assertThrows(SessionRejectedException.class, () -> authority.bootstrap(rotated.refreshToken()));
        } finally { pool.shutdownNow(); }
    }

    @Test void bootstrapRejectsHistoricalUnknownRenewal() {
        create();
        var authority = authority(allow);
        var issued = authority.issue(authority.createAuthenticated(evidence), a -> "jwt");
        Session current = sessions.loadLocked(issued.sessionId()).orElseThrow();
        Session historical = new Session(current.sessionId(), current.userId(), current.createdAt(), null,
                current.renewableUntil(), current.lastActivityAt(), current.idleUntil(),
                current.absoluteUntil(), false, current.version());
        sessions.data.put(current.sessionId(), historical);
        assertThrows(SessionRejectedException.class, () -> authority.bootstrap(issued.refreshToken()));
        assertEquals(historical, sessions.loadLocked(issued.sessionId()).orElseThrow());
    }

    private static final String TEST_ORIGIN = "https://app.markettec.example";
    private static final String TEST_JWT = "eyJhbGciOiJSUzI1NiJ9.eyJleHAiOjE3OTM3MDc0NDB9.c2ln";

    private MockMvc verificationMvc(SessionAuthority authority, InternalJwtService jwt) {
        return MockMvcBuilders.standaloneSetup(
                new SessionBootstrapController(authority, clock, TEST_ORIGIN, "https://api.markettec.example", true),
                new SessionRefreshController(authority, jwt, clock, TEST_ORIGIN, "https://api.markettec.example", true))
                .build();
    }

    @Test void httpBootstrapCookieOnlyAllowsManuallyConstructedClientHeaders() throws Exception {
        create();
        var authority = authority(allow);
        var issued = authority.issue(authority.createAuthenticated(evidence), a -> "synthetic-jwt");
        var mvc = verificationMvc(authority, mock(InternalJwtService.class));
        var cookie = new Cookie("__Host-mtec-refresh", issued.refreshToken());
        // MockMvc sends only the manually supplied headers. No bearer JWT or browser preflight is present.
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", TEST_ORIGIN)
                        .header("X-MTEC-Client", "web").cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.csrfToken").value(issued.csrfToken()))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", TEST_ORIGIN)
                        .header("X-MTEC-Client", "web"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("REFRESH_INVALID"));
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", TEST_ORIGIN)
                        .header("X-MTEC-Client", "web").cookie(new Cookie("__Host-mtec-refresh", "f".repeat(64))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", "https://other.example")
                        .header("X-MTEC-Client", "web").cookie(cookie))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        authority.revoke(issued.sessionId());
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", TEST_ORIGIN)
                        .header("X-MTEC-Client", "web").cookie(cookie))
                .andExpect(status().isUnauthorized());
        var later = authority.issue(authority.createAuthenticated(evidence), a -> "synthetic-jwt");
        clock.set(start.plusSeconds(1800));
        mvc.perform(get("/v1/auth/session").secure(true).header("Origin", TEST_ORIGIN)
                        .header("X-MTEC-Client", "web")
                        .cookie(new Cookie("__Host-mtec-refresh", later.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test void httpRefreshReplayReachesInternalConsumedClassification() throws Exception {
        create();
        var authority = authority(allow);
        var jwt = mock(InternalJwtService.class);
        when(jwt.issue(any())).thenReturn(TEST_JWT);
        var issued = authority.issue(authority.createAuthenticated(evidence), a -> "synthetic-jwt");
        var mvc = verificationMvc(authority, jwt);
        var original = post("/v1/auth/refresh").secure(true).header("Origin", TEST_ORIGIN)
                .header("X-MTEC-Client", "web").header("X-CSRF-Token", issued.csrfToken())
                .cookie(new Cookie("__Host-mtec-refresh", issued.refreshToken()))
                .contentType("application/json").content("{\"mode\":\"rotate\"}");
        var winner = mvc.perform(original).andExpect(status().isOk())
                .andExpect(jsonPath("$.csrfToken").exists()).andReturn();
        assertNotNull(winner.getResponse().getHeader("Set-Cookie"));
        var state = sessions.loadLocked(issued.sessionId()).orElseThrow();
        assertEquals(1L, sessions.generation.get(issued.sessionId()));
        var logger = (Logger) LoggerFactory.getLogger(SessionAuthority.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            mvc.perform(post("/v1/auth/refresh").secure(true).header("Origin", TEST_ORIGIN)
                            .header("X-MTEC-Client", "web").header("X-CSRF-Token", issued.csrfToken())
                            .cookie(new Cookie("__Host-mtec-refresh", issued.refreshToken()))
                            .contentType("application/json").content("{\"mode\":\"rotate\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("REFRESH_INVALID"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
            assertEquals(1, events.list.size());
            assertEquals("Consumed refresh generation reused", events.list.getFirst().getFormattedMessage());
            assertFalse(events.list.getFirst().getFormattedMessage().contains(issued.refreshToken()));
            assertFalse(events.list.getFirst().getFormattedMessage().contains(issued.csrfToken()));
            mvc.perform(post("/v1/auth/refresh").secure(true).header("Origin", TEST_ORIGIN)
                            .header("X-MTEC-Client", "web").header("X-CSRF-Token", "f".repeat(64))
                            .cookie(new Cookie("__Host-mtec-refresh", issued.refreshToken()))
                            .contentType("application/json").content("{\"mode\":\"rotate\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("REFRESH_INVALID"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
            assertEquals(1, events.list.size());
            mvc.perform(post("/v1/auth/refresh").secure(true).header("Origin", TEST_ORIGIN)
                            .header("X-MTEC-Client", "web").header("X-CSRF-Token", issued.csrfToken())
                            .cookie(new Cookie("__Host-mtec-refresh", "0".repeat(64)))
                            .contentType("application/json").content("{\"mode\":\"rotate\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("REFRESH_INVALID"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
            assertEquals(1, events.list.size());
            mvc.perform(post("/v1/auth/refresh").secure(true).header("Origin", TEST_ORIGIN)
                            .header("X-MTEC-Client", "web")
                            .cookie(new Cookie("__Host-mtec-refresh", issued.refreshToken()))
                            .contentType("application/json").content("{\"mode\":\"rotate\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
            assertEquals(1L, sessions.generation.get(issued.sessionId()));
            assertEquals(state, sessions.loadLocked(issued.sessionId()).orElseThrow());
            assertFalse(state.revoked());
            assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED,
                    assertThrows(SessionRejectedException.class, () -> authority.refresh(
                            issued.refreshToken(), issued.csrfToken(), a -> "unused")).reason());
            assertEquals(SessionRejectedException.Reason.PROOF_INVALID,
                    assertThrows(SessionRejectedException.class, () -> authority.refresh(
                            issued.refreshToken(), "f".repeat(64), a -> "unused")).reason());
            assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED,
                    assertThrows(SessionRejectedException.class, () -> authority.refreshWithSnapshot(
                            issued.refreshToken(), issued.csrfToken(), jwt)).reason());
            var responseCookie = winner.getResponse().getHeader("Set-Cookie");
            var nextRefresh = responseCookie.substring(responseCookie.indexOf('=') + 1, responseCookie.indexOf(';'));
            var nextCsrf = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(winner.getResponse().getContentAsString()).path("csrfToken").asText();
            authority.revoke(issued.sessionId());
            mvc.perform(post("/v1/auth/refresh").secure(true).header("Origin", TEST_ORIGIN)
                            .header("X-MTEC-Client", "web").header("X-CSRF-Token", nextCsrf)
                            .cookie(new Cookie("__Host-mtec-refresh", nextRefresh))
                            .contentType("application/json").content("{\"mode\":\"rotate\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
            verify(jwt, times(1)).issue(any());
        } finally { logger.detachAppender(events); events.stop(); }
    }

    @Test void simultaneousHttpRefreshHasOneWinnerAndNoDeadlineExtension() throws Exception {
        create();
        var authority = authority(allow);
        var jwt = mock(InternalJwtService.class);
        when(jwt.issue(any())).thenReturn(TEST_JWT);
        var issued = authority.issue(authority.createAuthenticated(evidence), a -> "synthetic-jwt");
        var mvc = verificationMvc(authority, jwt);
        var before = sessions.loadLocked(issued.sessionId()).orElseThrow();
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            Callable<Integer> action = () -> {
                gate.await();
                return mvc.perform(post("/v1/auth/refresh").secure(true).header("Origin", TEST_ORIGIN)
                                .header("X-MTEC-Client", "web").header("X-CSRF-Token", issued.csrfToken())
                                .cookie(new Cookie("__Host-mtec-refresh", issued.refreshToken()))
                                .contentType("application/json").content("{\"mode\":\"rotate\"}"))
                        .andReturn().getResponse().getStatus();
            };
            var first = pool.submit(action);
            var second = pool.submit(action);
            gate.countDown();
            assertEquals(Set.of(200, 401), Set.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)));
            assertEquals(1L, sessions.generation.get(issued.sessionId()));
            assertEquals(1, sessions.consumed.size());
            assertEquals(before, sessions.loadLocked(issued.sessionId()).orElseThrow());
            verify(jwt, times(1)).issue(any());
        } finally { pool.shutdownNow(); }
    }

    @Test void expiredHttpRefreshIssuesNoCredential() throws Exception {
        create();
        var authority = authority(allow);
        var jwt = mock(InternalJwtService.class);
        var issued = authority.issue(authority.createAuthenticated(evidence), a -> "synthetic-jwt");
        var mvc = verificationMvc(authority, jwt);
        clock.set(start.plusSeconds(1800));
        mvc.perform(post("/v1/auth/refresh").secure(true).header("Origin", TEST_ORIGIN)
                        .header("X-MTEC-Client", "web").header("X-CSRF-Token", issued.csrfToken())
                        .cookie(new Cookie("__Host-mtec-refresh", issued.refreshToken()))
                        .contentType("application/json").content("{\"mode\":\"rotate\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("REFRESH_INVALID"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verifyNoInteractions(jwt);
        assertEquals(0L, sessions.generation.get(issued.sessionId()));
    }

    private static final class MemorySessions implements SessionRepository {
        private final Map<String, Session> data = new HashMap<>();
        private final Map<String, SessionVerifiers> credentials = new HashMap<>();
        private final Map<String, ConsumedCredential> consumed = new HashMap<>();
        private final Map<String, ConsumedCredential> closed = new HashMap<>();
        private final Map<String, Long> generation = new HashMap<>();
        volatile boolean failAfterConsumption;
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
            if (next.revoked()) {
                SessionVerifiers values = credentials.remove(old.sessionId());
                if (values != null) closed.put(values.refreshHash(), new ConsumedCredential(next, values));
            }
        }
        public synchronized Optional<SessionVerifiers> verifiers(String id) { check(); return Optional.ofNullable(credentials.get(id)); }
        public synchronized void initializeVerifiers(String id, SessionVerifiers value) {
            check();
            if (credentials.putIfAbsent(id, value) != null) throw new IllegalStateException("duplicate");
            generation.put(id, 0L);
        }
        public synchronized void rotateVerifiers(String id, SessionVerifiers old, SessionVerifiers next, Instant consumedAt) {
            check();
            if (!credentials.get(id).equals(old)) throw new IllegalStateException("stale");
            if (consumed.putIfAbsent(old.refreshHash(), new ConsumedCredential(data.get(id), old)) != null)
                throw new IllegalStateException("duplicate");
            if (failAfterConsumption) throw new IllegalStateException("persistence failed");
            if (!credentials.replace(id, old, next)) throw new IllegalStateException("stale");
            generation.compute(id, (key, number) -> number + 1);
        }
        public synchronized Optional<ConsumedCredential> consumedByRefreshHashLocked(String hash) {
            check(); return Optional.ofNullable(consumed.get(hash));
        }
        public synchronized Optional<ConsumedCredential> closedByRefreshHashLocked(String hash) {
            check(); return Optional.ofNullable(closed.get(hash));
        }
        private void check() { if (fail) throw new IllegalStateException("store unavailable"); }
    }
}

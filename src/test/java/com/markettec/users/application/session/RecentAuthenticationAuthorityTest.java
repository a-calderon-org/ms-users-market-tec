package com.markettec.users.application.session;

import com.markettec.users.domain.model.UserId;
import java.security.SecureRandom;
import java.time.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecentAuthenticationAuthorityTest {
    private static final UserId USER = new UserId("usr_123e4567-e89b-42d3-a456-426614174000");
    private static final UserId OTHER = new UserId("usr_223e4567-e89b-42d3-a456-426614174000");
    private static final String SID = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_SID = "fedcba9876543210fedcba9876543210";
    private static final Instant START = Instant.parse("2026-11-03T12:00:00Z");
    private final AtomicReference<Instant> time = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
    };
    private final SessionAuthority sessions = mock(SessionAuthority.class);
    private final Map<String, RecentAttempt> data = new HashMap<>();
    private final RecentAttemptRepository repository = new RecentAttemptRepository() {
        public void insert(RecentAttempt a) { data.put(a.attemptId(), a); }
        public Optional<RecentAttempt> loadLocked(String id) { return Optional.ofNullable(data.get(id)); }
        public void replace(RecentAttempt before, RecentAttempt after) {
            if (!data.replace(before.attemptId(), before, after)) throw new IllegalStateException("stale");
        }
    };
    private final RecentAuthenticationAuthority authority;

    RecentAuthenticationAuthorityTest() {
        when(sessions.withCurrentSession(anyString(), any(), any())).thenAnswer(call -> {
            String sid = call.getArgument(0);
            UserId user = call.getArgument(1);
            if (!SID.equals(sid) || !USER.equals(user)) throw new SessionRejectedException();
            @SuppressWarnings("unchecked") java.util.function.Supplier<Object> work = call.getArgument(2);
            Map<String, RecentAttempt> before = new HashMap<>(data);
            try { return work.get(); }
            catch (RuntimeException failure) { data.clear(); data.putAll(before); throw failure; }
        });
        when(sessions.current(SID)).thenReturn(Optional.of(Session.start(SID, USER, START)));
        authority = new RecentAuthenticationAuthority(sessions, repository, clock, new SecureRandom());
    }

    private RecentAttempt begin() {
        return authority.begin(SID, USER, RecentAttempt.Method.INSTITUTIONAL_EMAIL,
                "DEACTIVATE_USER", USER.value());
    }

    private TrustedRecentEvidence evidence(RecentAttempt a, Instant event) {
        return new TrustedRecentEvidence(USER, SID, a.attemptId(), a.method(), a.operation(),
                a.target(), a.nonce(), event);
    }

    @Test void validAttemptIsBoundedAndSingleUse() {
        RecentAttempt a = begin();
        assertEquals(32, a.attemptId().length());
        assertEquals(64, a.nonce().length());
        assertEquals(START.plusSeconds(300), a.expiresAt());
        authority.recordVerified(evidence(a, START));
        assertEquals("done", authority.consume(SID, USER, a.attemptId(), a.method(), a.operation(), a.target(), () -> "done"));
        assertTrue(data.get(a.attemptId()).consumed());
        assertThrows(SessionRejectedException.class,
                () -> authority.consume(SID, USER, a.attemptId(), a.method(), a.operation(), a.target(), () -> "again"));
    }

    @Test void attemptExpiryAndEventAgeRejectAtExactBoundary() {
        RecentAttempt a = begin();
        authority.recordVerified(evidence(a, START));
        time.set(START.plusSeconds(300));
        assertThrows(SessionRejectedException.class,
                () -> authority.consume(SID, USER, a.attemptId(), a.method(), a.operation(), a.target(), () -> null));
        assertThrows(SessionRejectedException.class, () -> authority.recordVerified(evidence(begin(), START)));
    }

    @Test void futureMissingAndWrongEvidenceReject() {
        RecentAttempt a = begin();
        assertThrows(SessionRejectedException.class, () -> authority.recordVerified(null));
        assertThrows(SessionRejectedException.class, () -> authority.recordVerified(evidence(a, null)));
        assertThrows(SessionRejectedException.class, () -> authority.recordVerified(evidence(a, START.plusMillis(1))));
        assertThrows(SessionRejectedException.class, () -> authority.recordVerified(new TrustedRecentEvidence(
                OTHER, SID, a.attemptId(), a.method(), a.operation(), a.target(), a.nonce(), START)));
        assertThrows(SessionRejectedException.class, () -> authority.recordVerified(new TrustedRecentEvidence(
                USER, OTHER_SID, a.attemptId(), a.method(), a.operation(), a.target(), a.nonce(), START)));
        assertThrows(SessionRejectedException.class, () -> authority.recordVerified(new TrustedRecentEvidence(
                USER, SID, a.attemptId(), a.method(), "OTHER", a.target(), a.nonce(), START)));
        assertThrows(SessionRejectedException.class, () -> authority.recordVerified(new TrustedRecentEvidence(
                USER, SID, a.attemptId(), a.method(), a.operation(), "OTHER", a.nonce(), START)));
        assertThrows(SessionRejectedException.class, () -> authority.recordVerified(new TrustedRecentEvidence(
                USER, SID, a.attemptId(), a.method(), a.operation(), a.target(), "wrong", START)));
        assertThrows(SessionRejectedException.class, () -> authority.recordVerified(new TrustedRecentEvidence(
                USER, SID, a.attemptId(), RecentAttempt.Method.GOOGLE, a.operation(), a.target(), a.nonce(), START)));
        assertNull(data.get(a.attemptId()).authenticatedAt());
    }

    @Test void mutationFailureRollsBackConsumption() {
        RecentAttempt a = begin();
        authority.recordVerified(evidence(a, START));
        assertThrows(IllegalStateException.class, () -> authority.consume(SID, USER, a.attemptId(),
                a.method(), a.operation(), a.target(), () -> { throw new IllegalStateException(); }));
        assertFalse(data.get(a.attemptId()).consumed());
        authority.consume(SID, USER, a.attemptId(), a.method(), a.operation(), a.target(), () -> null);
        assertTrue(data.get(a.attemptId()).consumed());
    }

    @Test void sessionRejectionAndMissingVerifierFailClosed() {
        RecentAttempt a = begin();
        assertThrows(SessionRejectedException.class,
                () -> authority.consume(SID, USER, a.attemptId(), a.method(), a.operation(), a.target(), () -> null));
        authority.recordVerified(evidence(a, START));
        assertThrows(SessionRejectedException.class,
                () -> authority.consume(OTHER_SID, USER, a.attemptId(), a.method(), a.operation(), a.target(), () -> null));
        assertThrows(SessionRejectedException.class,
                () -> authority.consume(SID, USER, a.attemptId(), a.method(), "OTHER", a.target(), () -> null));
        assertThrows(SessionRejectedException.class,
                () -> authority.consume(SID, USER, a.attemptId(), a.method(), a.operation(), "OTHER", () -> null));
        verify(sessions, never()).renew(anyString());
    }
}

package com.markettec.users.application.session;

import com.markettec.users.domain.model.UserId;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

/** Internal authority. No production verifier is registered yet. */
public final class RecentAuthenticationAuthority {
    private final SessionAuthority sessions;
    private final RecentAttemptRepository attempts;
    private final Clock clock;
    private final SecureRandom random;

    public RecentAuthenticationAuthority(SessionAuthority sessions, RecentAttemptRepository attempts,
                                         Clock clock, SecureRandom random) {
        this.sessions = Objects.requireNonNull(sessions);
        this.attempts = Objects.requireNonNull(attempts);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    public RecentAttempt begin(String sessionId, UserId userId, RecentAttempt.Method method,
                               String operation, String target) {
        Objects.requireNonNull(method);
        return sessions.withCurrentSession(sessionId, userId, () -> {
            Session session = sessions.current(sessionId).orElseThrow(SessionRejectedException::new);
            Instant now = now();
            Instant expires = now.plusSeconds(300);
            if (session.deadline().isBefore(expires)) expires = session.deadline();
            if (!expires.isAfter(now)) throw new SessionRejectedException();
            RecentAttempt attempt = new RecentAttempt(randomHex(16), userId, sessionId, method,
                    operation, target, randomHex(32), now, expires, null, false, 0);
            attempts.insert(attempt);
            return attempt;
        });
    }

    /** Package access deliberately excludes public adapters and arbitrary DTOs. */
    void recordVerified(TrustedRecentEvidence evidence) {
        if (evidence == null || evidence.userId == null) throw new SessionRejectedException();
        sessions.withCurrentSession(evidence.sessionId, evidence.userId, () -> {
            RecentAttempt before = attempts.loadLocked(evidence.attemptId).orElseThrow(SessionRejectedException::new);
            Instant now = now();
            requireBinding(before, evidence.userId, evidence.sessionId, evidence.method,
                    evidence.operation, evidence.target, evidence.nonce);
            if (!before.validAt(now) || evidence.authenticatedAt == null
                    || evidence.authenticatedAt.isAfter(now)
                    || Duration.between(evidence.authenticatedAt, now).compareTo(Duration.ofSeconds(300)) >= 0)
                throw new SessionRejectedException();
            attempts.replace(before, before.verifiedAt(evidence.authenticatedAt));
            return null;
        });
    }

    public <T> T consume(String sessionId, UserId userId, String attemptId,
                         RecentAttempt.Method method, String operation, String target, Supplier<T> mutation) {
        Objects.requireNonNull(mutation);
        return consumeWith(sessionId, userId, attemptId, method, operation, target, attempt -> mutation.get());
    }

    /** Same single-use contract; the mutation receives the bound attempt (e.g. its nonce). */
    public <T> T consumeWith(String sessionId, UserId userId, String attemptId,
                             RecentAttempt.Method method, String operation, String target,
                             java.util.function.Function<RecentAttempt, T> mutation) {
        Objects.requireNonNull(mutation);
        return sessions.withCurrentSession(sessionId, userId, () -> {
            RecentAttempt before = attempts.loadLocked(attemptId).orElseThrow(SessionRejectedException::new);
            Instant now = now();
            requireBinding(before, userId, sessionId, method, operation, target, before.nonce());
            if (!before.validAt(now) || before.authenticatedAt() == null
                    || before.authenticatedAt().isAfter(now)
                    || Duration.between(before.authenticatedAt(), now).compareTo(Duration.ofSeconds(300)) >= 0)
                throw new SessionRejectedException();
            attempts.replace(before, before.consume());
            T result = mutation.apply(before);
            Instant commitTime = now();
            if (!commitTime.isBefore(before.expiresAt())
                    || Duration.between(before.authenticatedAt(), commitTime).compareTo(Duration.ofSeconds(300)) >= 0)
                throw new SessionRejectedException();
            return result;
        });
    }

    private static void requireBinding(RecentAttempt a, UserId userId, String sid,
                                       RecentAttempt.Method method, String operation, String target, String nonce) {
        if (!a.userId().equals(userId) || !a.sessionId().equals(sid) || a.method() != method
                || !a.operation().equals(operation) || !a.target().equals(target)
                || !a.nonce().equals(nonce)) throw new SessionRejectedException();
    }

    private String randomHex(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}

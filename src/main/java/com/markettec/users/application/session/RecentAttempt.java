package com.markettec.users.application.session;

import com.markettec.users.domain.model.UserId;
import java.time.Instant;
import java.util.Objects;

/** Immutable binding; only verification and consumption state may advance. */
public record RecentAttempt(String attemptId, UserId userId, String sessionId, Method method,
                            String operation, String target, String nonce, Instant createdAt,
                            Instant expiresAt, Instant authenticatedAt, boolean consumed, long version) {
    public enum Method { INSTITUTIONAL_EMAIL, GOOGLE, PASSKEY }

    public RecentAttempt {
        if (attemptId == null || !attemptId.matches("[0-9a-f]{32}") || userId == null
                || sessionId == null || !sessionId.matches("[0-9a-f]{32}") || method == null
                || operation == null || operation.isBlank() || target == null || target.isBlank()
                || nonce == null || !nonce.matches("[0-9a-f]{64}") || createdAt == null
                || expiresAt == null || !expiresAt.isAfter(createdAt)
                || expiresAt.isAfter(createdAt.plusSeconds(300)) || version < 0
                || (authenticatedAt != null && authenticatedAt.isBefore(createdAt))
                || (consumed && authenticatedAt == null)) throw new IllegalArgumentException("Invalid recent attempt");
    }

    public boolean validAt(Instant now) { return !consumed && now.isBefore(expiresAt); }

    public RecentAttempt verifiedAt(Instant eventTime) {
        if (authenticatedAt != null || consumed) throw new SessionRejectedException();
        return new RecentAttempt(attemptId, userId, sessionId, method, operation, target, nonce,
                createdAt, expiresAt, Objects.requireNonNull(eventTime), false, version + 1);
    }

    public RecentAttempt consume() {
        if (authenticatedAt == null || consumed) throw new SessionRejectedException();
        return new RecentAttempt(attemptId, userId, sessionId, method, operation, target, nonce,
                createdAt, expiresAt, authenticatedAt, true, version + 1);
    }
}

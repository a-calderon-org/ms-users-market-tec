package com.markettec.users.application.session;

import java.time.Instant;

/** Server-issued preauthentication state, independent of B04 attempts. */
public record GoogleLoginIntent(String attemptId, String nonce, String browserHash,
                                Instant createdAt, Instant expiresAt, boolean consumed, long version) {
    public GoogleLoginIntent {
        if (attemptId == null || !attemptId.matches("[0-9a-f]{32}")
                || nonce == null || !nonce.matches("[0-9a-f]{64}")
                || browserHash == null || !browserHash.matches("[0-9a-f]{64}")
                || createdAt == null || expiresAt == null || !expiresAt.isAfter(createdAt)
                || expiresAt.isAfter(createdAt.plusSeconds(300)) || version < 0)
            throw new IllegalArgumentException("Invalid Google login intent");
    }

    public boolean validAt(Instant now) { return !consumed && now.isBefore(expiresAt); }

    public GoogleLoginIntent consume() {
        if (consumed) throw new SessionRejectedException();
        return new GoogleLoginIntent(attemptId, nonce, browserHash, createdAt, expiresAt, true, version + 1);
    }
}

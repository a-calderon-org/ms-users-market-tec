package com.markettec.users.application.session;

import com.markettec.users.domain.model.Email;
import java.time.Instant;
import java.util.Objects;

/** Single-use Magic Link challenge. Only a salted SHA-256 verifier of the secret is retained. */
public record EmailLoginAttempt(String attemptId, Email recipient, String verifier, Instant createdAt,
                                Instant expiresAt, int failures, boolean consumed, long version) {
    public static final int MAX_FAILURES = 5;

    public EmailLoginAttempt {
        if (attemptId == null || !attemptId.matches("[0-9a-f]{32}") || verifier == null
                || !verifier.matches("[0-9a-f]{64}") || createdAt == null || expiresAt == null
                || !expiresAt.isAfter(createdAt) || failures < 0 || version < 0)
            throw new IllegalArgumentException("Invalid email login attempt");
        Objects.requireNonNull(recipient);
    }

    public boolean validAt(Instant now) {
        return !consumed && failures < MAX_FAILURES && now.isBefore(expiresAt);
    }

    public EmailLoginAttempt consume() {
        return new EmailLoginAttempt(attemptId, recipient, verifier, createdAt, expiresAt, failures, true, version + 1);
    }

    public EmailLoginAttempt failed() {
        return new EmailLoginAttempt(attemptId, recipient, verifier, createdAt, expiresAt, failures + 1, consumed, version + 1);
    }
}

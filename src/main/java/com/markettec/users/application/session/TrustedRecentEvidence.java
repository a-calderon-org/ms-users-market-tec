package com.markettec.users.application.session;

import com.markettec.users.domain.model.UserId;
import java.time.Instant;

/** Constructible only by a verifier in this package after real authentication. */
public final class TrustedRecentEvidence {
    final UserId userId;
    final String sessionId, attemptId, operation, target, nonce;
    final RecentAttempt.Method method;
    final Instant authenticatedAt;

    TrustedRecentEvidence(UserId userId, String sessionId, String attemptId,
                          RecentAttempt.Method method, String operation, String target,
                          String nonce, Instant authenticatedAt) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.attemptId = attemptId;
        this.method = method;
        this.operation = operation;
        this.target = target;
        this.nonce = nonce;
        this.authenticatedAt = authenticatedAt;
    }
}

package com.markettec.users.application.session;

import com.markettec.users.domain.model.UserId;
import java.time.Instant;

/** One-use authorization, bound to a session, to set the first password after a confirmed Magic Link. */
public interface PasswordSetupGrantRepository {
    void issue(String sessionId, UserId userId, Instant createdAt, Instant expiresAt);
    /** Whether an unspent, unexpired grant exists for exactly this session and user. */
    boolean live(String sessionId, UserId userId, Instant now);
    /** Spends the grant atomically; false when none is live. */
    boolean consume(String sessionId, UserId userId, Instant now);
}

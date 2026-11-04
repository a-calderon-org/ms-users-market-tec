package com.markettec.users.application.session;

import java.util.Optional;
import java.time.Instant;

/** All methods execute within the caller's transaction; load locks the session until commit. */
public interface SessionRepository {
    void insert(Session session);
    Optional<Session> loadLocked(String sessionId);
    Optional<Session> loadByRefreshHashLocked(String refreshHash);
    void replace(Session expected, Session replacement);
    /** Only SHA-256 verifiers are persisted. Caller holds the session row lock. */
    Optional<SessionVerifiers> verifiers(String sessionId);
    void initializeVerifiers(String sessionId, SessionVerifiers values);
    void rotateVerifiers(String sessionId, SessionVerifiers expected, SessionVerifiers replacement, Instant consumedAt);
    /** Only matching, consumed proofs are returned; never grants authorization. */
    Optional<ConsumedCredential> consumedByRefreshHashLocked(String refreshHash);
    /** Last unconsumed generation, read only after taking its owning session lock. */
    Optional<ConsumedCredential> closedByRefreshHashLocked(String refreshHash);
    record ConsumedCredential(Session session, SessionVerifiers verifiers) { }
}

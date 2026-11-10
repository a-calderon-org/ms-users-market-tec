package com.markettec.users.application.session;

import java.time.Instant;
import java.util.Optional;

/** Failure counter keyed by an opaque digest. All methods run inside the caller's transaction. */
public interface PasswordAttemptThrottle {
    record State(int failures, Instant windowStart, Instant lockedUntil) { }
    Optional<State> loadLocked(String keyHash);
    void save(String keyHash, State state);
    void clear(String keyHash);
}

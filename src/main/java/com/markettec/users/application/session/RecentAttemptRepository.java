package com.markettec.users.application.session;

import java.util.Optional;

/** Caller holds the session lock before taking the attempt lock. */
public interface RecentAttemptRepository {
    void insert(RecentAttempt attempt);
    Optional<RecentAttempt> loadLocked(String attemptId);
    void replace(RecentAttempt before, RecentAttempt after);
}

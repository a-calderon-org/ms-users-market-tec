package com.markettec.users.application.session;

import java.time.Instant;
import java.util.Optional;

/** All methods run inside the caller's transaction. */
public interface EmailLoginAttemptRepository {
    void insert(EmailLoginAttempt attempt);
    Optional<EmailLoginAttempt> loadLocked(String attemptId);
    void replace(EmailLoginAttempt before, EmailLoginAttempt after);
    /** Requests recorded for this normalized recipient since the instant (throttling). */
    int countSince(String normalizedRecipient, Instant since);
}

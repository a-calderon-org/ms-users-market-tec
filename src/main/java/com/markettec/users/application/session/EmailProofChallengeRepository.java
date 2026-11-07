package com.markettec.users.application.session;

import java.util.Optional;

/** Verifier-only storage for the email link bound to one recent-authentication attempt. */
public interface EmailProofChallengeRepository {
    /** Fails if the attempt already has a challenge (one link per attempt). */
    void insert(String attemptId, String verifier);
    /** Returns the verifier only while unconsumed; the caller holds the attempt lock. */
    Optional<String> loadOpenLocked(String attemptId);
    void markConsumed(String attemptId);
}

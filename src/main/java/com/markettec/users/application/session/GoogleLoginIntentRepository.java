package com.markettec.users.application.session;

import java.util.Optional;

/** Lock the intent through the transaction that creates the session. */
public interface GoogleLoginIntentRepository {
    void insert(GoogleLoginIntent intent);
    Optional<GoogleLoginIntent> loadLocked(String attemptId);
    void replace(GoogleLoginIntent before, GoogleLoginIntent after);
}

package com.markettec.users.application.session;

import java.util.Optional;

public interface PendingGoogleRegistrationRepository {
    void insert(PendingGoogleRegistration pending);
    Optional<PendingGoogleRegistration> loadLocked(String attemptId);
    void replace(PendingGoogleRegistration before, PendingGoogleRegistration after);
    boolean historicalEmailExists(String email);
}

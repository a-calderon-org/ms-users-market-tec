package com.markettec.users.application.session;

import com.markettec.users.domain.model.UserId;
import java.util.Optional;

/** All methods run inside the caller's transaction; a load takes the row (or its absence) lock until commit. */
public interface PasswordCredentialRepository {
    Optional<PasswordCredential> findLocked(UserId userId);
    void insert(PasswordCredential credential);
    void replace(PasswordCredential before, PasswordCredential after);
}

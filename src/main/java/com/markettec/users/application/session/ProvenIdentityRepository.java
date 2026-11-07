package com.markettec.users.application.session;

import com.markettec.users.domain.model.ExternalIdentityKey;
import java.util.Optional;

/** Which exact identity a recent proof demonstrated; callers hold the attempt lock. */
public interface ProvenIdentityRepository {
    /** One identity per attempt; a second record fails. */
    void record(String attemptId, ExternalIdentityKey key);
    Optional<ExternalIdentityKey> find(String attemptId);
}

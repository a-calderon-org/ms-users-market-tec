package com.markettec.users.application.session;

import java.util.Optional;

/** All methods execute within the caller's transaction; load locks the session until commit. */
public interface SessionRepository {
    void insert(Session session);
    Optional<Session> loadLocked(String sessionId);
    void replace(Session expected, Session replacement);
}

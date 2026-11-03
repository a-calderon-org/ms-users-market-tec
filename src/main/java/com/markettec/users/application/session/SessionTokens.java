package com.markettec.users.application.session;

import java.time.Instant;

/** Plain credentials are returned only from issuance or successful rotation. */
public record SessionTokens(String jwt, String refreshToken, String csrfToken,
                            String sessionId, String userId, Instant sessionDeadline) {
    @Override public String toString() {
        return "SessionTokens[credentials=REDACTED, sessionId=" + sessionId + "]";
    }
}

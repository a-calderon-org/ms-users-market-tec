package com.markettec.users.application.session;

import com.markettec.users.domain.model.Email;
import java.time.Instant;

/** Google evidence retained without the original ID token or link secret. */
public record PendingGoogleRegistration(String attemptId, String correlationId, String issuer, String subject, Email recipient,
        String verifier, Instant expiresAt, State state, long version) {
    public enum State { PENDING, LINK_ISSUED, CONFIRMED }
    public PendingGoogleRegistration {
        if (attemptId == null || !attemptId.matches("[0-9a-f]{32}")
                || correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,128}")
                || issuer == null || issuer.isBlank()
                || subject == null || subject.isBlank() || recipient == null || expiresAt == null
                || state == null || version < 0 || (verifier != null && !verifier.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid pending registration");
    }
    public PendingGoogleRegistration withVerifier(String hash) {
        if (state != State.PENDING) throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_CONSUMED);
        return new PendingGoogleRegistration(attemptId, correlationId, issuer, subject, recipient, hash, expiresAt,
                State.LINK_ISSUED, version + 1);
    }
    public PendingGoogleRegistration confirm() {
        if (state != State.LINK_ISSUED) throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_CONSUMED);
        return new PendingGoogleRegistration(attemptId, correlationId, issuer, subject, recipient, verifier, expiresAt,
                State.CONFIRMED, version + 1);
    }
}

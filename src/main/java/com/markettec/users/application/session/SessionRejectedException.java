package com.markettec.users.application.session;

/** Deliberately contains no session identifier or credential material. */
public final class SessionRejectedException extends RuntimeException {
    public enum Reason { PROOF_INVALID, ATTEMPT_CONSUMED, ATTEMPT_EXPIRED, IDENTITY_CONFLICT, CONTINUITY_REQUIRED }
    private final Reason reason;
    public SessionRejectedException() { this(Reason.PROOF_INVALID); }
    public SessionRejectedException(Reason reason) {
        super("Session operation rejected");
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}

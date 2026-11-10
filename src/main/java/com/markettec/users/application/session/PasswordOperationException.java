package com.markettec.users.application.session;

/** Non-revealing, code-only failure of a password operation. */
public final class PasswordOperationException extends RuntimeException {
    public enum Code {
        WEAK_PASSWORD, PASSWORD_ALREADY_SET, PASSWORD_NOT_SET, SETUP_NOT_AUTHORIZED, INVALID_CREDENTIALS,
        THROTTLED, UNAVAILABLE
    }
    private final Code code;
    private final long retryAfterSeconds;
    public PasswordOperationException(Code code) { this(code, 0); }
    public PasswordOperationException(Code code, long retryAfterSeconds) {
        super("Password operation rejected");
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public Code code() { return code; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}

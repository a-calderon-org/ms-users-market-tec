package com.markettec.users.application.session;

/** Deliberately contains no session identifier or credential material. */
public final class SessionRejectedException extends RuntimeException {
    public SessionRejectedException() { super("Session operation rejected"); }
}

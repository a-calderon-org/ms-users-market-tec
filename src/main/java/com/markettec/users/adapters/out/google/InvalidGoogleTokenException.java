package com.markettec.users.adapters.out.google;

/** Deliberately omits the token, claims, key material and underlying exception. */
public final class InvalidGoogleTokenException extends RuntimeException {
    public InvalidGoogleTokenException() {
        super("Invalid Google ID token");
    }
}

package com.markettec.users.domain.model;

/** Stable internal reasons, not an HTTP error contract. Messages contain no identity data. */
public final class UserDomainException extends RuntimeException {
    public enum Reason {
        UNAUTHORIZED, USER_NOT_FOUND, USER_DEACTIVATED, USER_ALREADY_EXISTS,
        IDENTITY_CONFLICT, CONCURRENT_MODIFICATION
    }

    private final Reason reason;

    public UserDomainException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}

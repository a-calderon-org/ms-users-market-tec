package com.markettec.users.adapters.out.sql;

/** Technical failure, deliberately separate from business reasons and SQL diagnostics. */
public final class UserPersistenceException extends RuntimeException {
    public UserPersistenceException(String message) {
        super(message);
    }
}

package com.markettec.users.application.session;

/** Persisted verifiers; never contains recoverable refresh or CSRF credentials. */
public record SessionVerifiers(String refreshHash, String csrfHash) {
    public SessionVerifiers {
        if (refreshHash == null || !refreshHash.matches("[0-9a-f]{64}")
                || csrfHash == null || !csrfHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid session verifiers");
    }
    @Override public String toString() { return "SessionVerifiers[REDACTED]"; }
}

package com.markettec.users.application.session;

/** Persisted verifiers; never contains recoverable refresh or CSRF credentials. */
public record SessionVerifiers(String refreshHash, String csrfHash, long generation, String keyId) {
    public SessionVerifiers {
        if (refreshHash == null || !refreshHash.matches("[0-9a-f]{64}")
                || csrfHash == null || !csrfHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid session verifiers");
        if (generation < 0 || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,32}"))
            throw new IllegalArgumentException("Invalid credential generation");
    }
    @Override public String toString() { return "SessionVerifiers[REDACTED]"; }
}

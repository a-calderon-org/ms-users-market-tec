package com.markettec.users.application.session;

/** Opaque issuance capability minted only by SessionAuthority after verified identity evidence. */
public final class AuthenticatedSession {
    private final String sessionId;

    AuthenticatedSession(String sessionId) { this.sessionId = sessionId; }

    public String sessionId() { return sessionId; }
}

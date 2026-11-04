package com.markettec.users.application.ports.out;

import java.time.Instant;
import com.markettec.users.domain.model.Email;

/** Validated external identifiers only; neither a MarketTec user nor a login session. */
public record VerifiedGoogleIdentity(String issuer, String subject, String audience,
                                     Instant issuedAt, Instant expiresAt, Email verifiedEmail) {
    public VerifiedGoogleIdentity(String issuer, String subject, String audience,
                                  Instant issuedAt, Instant expiresAt) {
        this(issuer, subject, audience, issuedAt, expiresAt, null);
    }
    @Override public String toString() {
        return "VerifiedGoogleIdentity[issuer=" + issuer + ", subject=<redacted>, audience="
                + audience + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt
                + ", verifiedEmail=<redacted>]";
    }
}

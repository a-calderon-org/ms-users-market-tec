package com.markettec.users.application.ports.out;

import java.time.Instant;
import com.markettec.users.domain.model.Email;

/**
 * Validated external identifiers only; neither a MarketTec user nor a login session. {@code givenName} and
 * {@code familyName} are Google's optional profile claims, already sanitized to valid profile values or null;
 * they only prefill a new account and never identify anyone.
 */
public record VerifiedGoogleIdentity(String issuer, String subject, String audience,
                                     Instant issuedAt, Instant expiresAt, Email verifiedEmail,
                                     String givenName, String familyName) {
    public VerifiedGoogleIdentity(String issuer, String subject, String audience,
                                  Instant issuedAt, Instant expiresAt, Email verifiedEmail) {
        this(issuer, subject, audience, issuedAt, expiresAt, verifiedEmail, null, null);
    }
    public VerifiedGoogleIdentity(String issuer, String subject, String audience,
                                  Instant issuedAt, Instant expiresAt) {
        this(issuer, subject, audience, issuedAt, expiresAt, null);
    }
    @Override public String toString() {
        return "VerifiedGoogleIdentity[issuer=" + issuer + ", subject=<redacted>, audience="
                + audience + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt
                + ", verifiedEmail=<redacted>, givenName=<redacted>, familyName=<redacted>]";
    }
}

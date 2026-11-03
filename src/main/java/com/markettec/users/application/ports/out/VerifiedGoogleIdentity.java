package com.markettec.users.application.ports.out;

import java.time.Instant;

/** Validated external identifiers only; neither a MarketTec user nor a login session. */
public record VerifiedGoogleIdentity(String issuer, String subject, String audience,
                                     Instant issuedAt, Instant expiresAt) { }

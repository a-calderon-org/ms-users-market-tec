package com.markettec.users.application.ports.in;

import com.markettec.users.domain.model.UserId;
import java.time.Instant;

/** Identity established by signature and claim validation, without live session status. */
public record VerifiedInternalToken(UserId userId, String sessionId, String jti,
                                    Instant issuedAt, Instant expiresAt) {}

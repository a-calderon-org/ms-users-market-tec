package com.markettec.users.domain.model;

import java.util.Objects;

/** Ownership is immutable; this domain provides no reassignment operation. */
public record ExternalIdentity(IdentityProvider provider, ExternalIdentityKey key,
                               UserId userId, Email verifiedEmail) {
    public ExternalIdentity {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(key);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(verifiedEmail);
    }
}

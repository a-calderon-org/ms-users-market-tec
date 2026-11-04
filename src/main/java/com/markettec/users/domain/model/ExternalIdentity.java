package com.markettec.users.domain.model;

import java.util.Objects;

/** Ownership is immutable; this domain provides no reassignment operation. */
public record ExternalIdentity(IdentityProvider provider, ExternalIdentityKey key,
                               UserId userId, Email verifiedEmail, IdentityLinkStatus status) {
    public ExternalIdentity {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(key);
        Objects.requireNonNull(userId);
        Objects.requireNonNull(verifiedEmail);
        Objects.requireNonNull(status);
    }
    /** New links are active only after the existing explicit link authorization succeeds. */
    public ExternalIdentity(IdentityProvider provider, ExternalIdentityKey key, UserId userId, Email verifiedEmail) {
        this(provider, key, userId, verifiedEmail, IdentityLinkStatus.ACTIVE);
    }

    public ExternalIdentity withStatus(IdentityLinkStatus newStatus) {
        return new ExternalIdentity(provider, key, userId, verifiedEmail, newStatus);
    }
}

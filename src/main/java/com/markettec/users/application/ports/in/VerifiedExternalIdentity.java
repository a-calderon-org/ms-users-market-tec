package com.markettec.users.application.ports.in;

import com.markettec.users.domain.model.Email;
import com.markettec.users.domain.model.ExternalIdentityKey;
import com.markettec.users.domain.model.IdentityProvider;
import java.util.Objects;

/**
 * Trusted-adapter output ONLY, not a client request DTO or proof by construction.
 * Before invoking the use case the adapter must verify provider evidence, issuer,
 * subject, address ownership, institutional eligibility and freshness/replay policy.
 * No such verifier is implemented in MTEC-34. Never bind this type to HTTP input.
 */
public record VerifiedExternalIdentity(IdentityProvider provider, ExternalIdentityKey key, Email email) {
    public VerifiedExternalIdentity {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(key);
        Objects.requireNonNull(email);
    }
}

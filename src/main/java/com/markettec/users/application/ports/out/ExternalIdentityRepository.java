package com.markettec.users.application.ports.out;

import com.markettec.users.domain.model.ExternalIdentity;
import com.markettec.users.domain.model.ExternalIdentityKey;
import com.markettec.users.domain.model.IdentityLinkStatus;
import com.markettec.users.domain.model.UserId;
import java.util.List;
import java.util.Optional;

public interface ExternalIdentityRepository {
    Optional<ExternalIdentity> findByKey(ExternalIdentityKey key);

    /** Every identity link owned by the account, regardless of status. */
    List<ExternalIdentity> findByUser(UserId userId);

    /**
     * Atomic insert-if-absent by (issuer, subject), irrespective of provider.
     * Same owner/provider is an idempotent no-op; a different owner/provider fails.
     * Requires an existing active owner; never reassigns or silently overwrites evidence.
     */
    ExternalIdentity insertOrGet(ExternalIdentity identity);

    /** Atomic status transition; ownership and evidence remain immutable. */
    void replaceStatus(ExternalIdentity expected, IdentityLinkStatus status);
}

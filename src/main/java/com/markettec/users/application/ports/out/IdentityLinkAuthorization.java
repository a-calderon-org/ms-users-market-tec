package com.markettec.users.application.ports.out;

import com.markettec.users.application.ports.in.AuthenticatedPrincipal;
import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.domain.model.UserId;

/**
 * Must throw unless an explicit grant binds this authenticated owner, target account
 * and exact external identity. Owning the account alone is insufficient.
 * The trusted adapter must establish fresh proof/control of both methods and enforce
 * expiry, replay and consumption policy. No HTTP boolean can establish this grant.
 * Future transactional grant consumption must roll back with UserTransactions.
 */
@FunctionalInterface
public interface IdentityLinkAuthorization {
    void requireAuthorization(AuthenticatedPrincipal principal, UserId target, VerifiedExternalIdentity identity);
}

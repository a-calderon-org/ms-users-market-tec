package com.markettec.users.application.ports.in;

import com.markettec.users.domain.model.*;
import java.net.URI;

public interface UserUseCases {
    /** Trusted internal provisioning only; does not authenticate or verify the address. */
    User createUser(Email email, ProfileDetails details, URI photo);

    /** Caller already verified identity and separately authorized new-account provisioning. */
    User registerVerifiedIdentity(VerifiedExternalIdentity identity, ProfileDetails details, URI photo);

    ExternalIdentity linkExternalIdentity(AuthenticatedPrincipal principal, UserId target,
                                          VerifiedExternalIdentity identity);
    UserProfile getOwnProfile(AuthenticatedPrincipal principal, UserId target);
    UserProfile updateOwnProfile(AuthenticatedPrincipal principal, UserId target, ProfileDetails details);
    User deactivateUser(AuthenticatedPrincipal principal, UserId target);
}

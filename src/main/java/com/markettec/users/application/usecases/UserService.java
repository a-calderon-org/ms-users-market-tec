package com.markettec.users.application.usecases;

import com.markettec.users.application.ports.in.AuthenticatedPrincipal;
import com.markettec.users.application.ports.in.UserUseCases;
import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.application.ports.out.*;
import com.markettec.users.domain.model.*;

import java.net.URI;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import static com.markettec.users.domain.model.UserDomainException.Reason.*;

public final class UserService implements UserUseCases {
    private final UserRepository users;
    private final ExternalIdentityRepository identities;
    private final UserIdGenerator ids;
    private final UserTransactions transactions;
    private final IdentityLinkAuthorization linkAuthorization;
    private final Clock clock;

    public UserService(UserRepository users, ExternalIdentityRepository identities, UserIdGenerator ids,
                       UserTransactions transactions, IdentityLinkAuthorization linkAuthorization, Clock clock) {
        this.users = Objects.requireNonNull(users);
        this.identities = Objects.requireNonNull(identities);
        this.ids = Objects.requireNonNull(ids);
        this.transactions = Objects.requireNonNull(transactions);
        this.linkAuthorization = Objects.requireNonNull(linkAuthorization);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public User createUser(Email email, ProfileDetails details, URI photo) {
        return transactions.execute(() -> {
            User user = User.create(ids.nextId(), email, details, photo, clock.instant());
            users.insert(user);
            return user;
        });
    }

    @Override
    public User registerVerifiedIdentity(VerifiedExternalIdentity identity, ProfileDetails details, URI photo) {
        Objects.requireNonNull(identity);
        return transactions.execute(() -> {
            var existing = identities.findByKey(identity.key());
            if (existing.isPresent()) {
                requireSameProvider(existing.get(), identity);
                return activeUser(existing.get().userId());
            }
            User user = User.create(ids.nextId(), identity.email(), details, photo, clock.instant())
                    .verifyProfileEmail(identity.email());
            users.insert(user);
            identities.insertOrGet(new ExternalIdentity(identity.provider(), identity.key(),
                    user.userId(), identity.email()));
            return user;
        });
    }

    @Override
    public Optional<UserId> resolveVerifiedIdentity(VerifiedExternalIdentity identity) {
        Objects.requireNonNull(identity);
        return transactions.execute(() -> identities.findByKey(identity.key()).map(existing -> {
            requireSameProvider(existing, identity);
            return activeUser(existing.userId()).userId();
        }));
    }

    @Override
    public ExternalIdentity linkExternalIdentity(AuthenticatedPrincipal principal, UserId target,
                                                 VerifiedExternalIdentity identity) {
        AuthenticatedPrincipal owner = requireOwner(principal, target);
        Objects.requireNonNull(identity);
        return transactions.execute(() -> {
            User user = activeUser(target);
            linkAuthorization.requireAuthorization(owner, target, identity);
            ExternalIdentity linked = identities.insertOrGet(new ExternalIdentity(identity.provider(),
                    identity.key(), target, identity.email()));
            // Use the original persisted evidence on retries, never overwrite it from a fresh request.
            users.replace(user, user.verifyProfileEmail(linked.verifiedEmail()));
            return linked;
        });
    }

    @Override
    public UserProfile getOwnProfile(AuthenticatedPrincipal principal, UserId target) {
        requireOwner(principal, target);
        return transactions.execute(() -> activeUser(target).profile());
    }

    @Override
    public UserProfile updateOwnProfile(AuthenticatedPrincipal principal, UserId target, ProfileDetails details) {
        requireOwner(principal, target);
        return transactions.execute(() -> {
            User before = activeUser(target);
            User after = before.updateProfile(details, clock.instant());
            users.replace(before, after);
            return after.profile();
        });
    }

    @Override
    public User deactivateUser(AuthenticatedPrincipal principal, UserId target) {
        requireOwner(principal, target);
        return transactions.execute(() -> {
            User before = users.findById(target).orElseThrow(() -> new UserDomainException(USER_NOT_FOUND));
            User after = before.deactivate();
            users.replace(before, after);
            return after;
        });
    }

    private User activeUser(UserId id) {
        User user = users.findById(id).orElseThrow(() -> new UserDomainException(USER_NOT_FOUND));
        user.requireActive();
        return user;
    }

    private AuthenticatedPrincipal requireOwner(AuthenticatedPrincipal principal, UserId target) {
        UserId authenticatedId = principal == null ? null : principal.userId();
        if (target == null || authenticatedId == null || !authenticatedId.equals(target)) {
            throw new UserDomainException(UNAUTHORIZED);
        }
        // Snapshot the trusted context so the authorization adapter sees the same identity.
        return () -> authenticatedId;
    }

    private void requireSameProvider(ExternalIdentity existing, VerifiedExternalIdentity identity) {
        if (existing.provider() != identity.provider()) {
            throw new UserDomainException(IDENTITY_CONFLICT);
        }
    }
}

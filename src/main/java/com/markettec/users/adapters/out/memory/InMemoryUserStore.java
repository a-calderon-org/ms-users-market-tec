package com.markettec.users.adapters.out.memory;

import com.markettec.users.application.ports.out.ExternalIdentityRepository;
import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.ports.out.UserTransactions;
import com.markettec.users.domain.model.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static com.markettec.users.domain.model.UserDomainException.Reason.*;

/** Single-process adapter. One instance supplies both repositories and transaction boundary. */
public final class InMemoryUserStore implements UserRepository, ExternalIdentityRepository, UserTransactions {
    private Map<UserId, User> users = new HashMap<>();
    private Map<ExternalIdentityKey, ExternalIdentity> identities = new HashMap<>();

    @Override
    public synchronized <T> T execute(Supplier<T> work) {
        Map<UserId, User> previousUsers = new HashMap<>(users);
        Map<ExternalIdentityKey, ExternalIdentity> previousIdentities = new HashMap<>(identities);
        try {
            return work.get();
        } catch (RuntimeException | Error failure) {
            users = previousUsers;
            identities = previousIdentities;
            throw failure;
        }
    }

    @Override
    public synchronized Optional<User> findById(UserId id) {
        return Optional.ofNullable(users.get(Objects.requireNonNull(id)));
    }

    @Override
    public synchronized void insert(User user) {
        Objects.requireNonNull(user);
        if (users.putIfAbsent(user.userId(), user) != null) {
            throw new UserDomainException(USER_ALREADY_EXISTS);
        }
    }

    @Override
    public synchronized void replace(User expected, User replacement) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(replacement);
        if (!expected.userId().equals(replacement.userId())) {
            throw new IllegalArgumentException("Cannot change account ownership");
        }
        if (!users.replace(expected.userId(), expected, replacement)) {
            throw new UserDomainException(CONCURRENT_MODIFICATION);
        }
    }

    @Override
    public synchronized Optional<ExternalIdentity> findByKey(ExternalIdentityKey key) {
        return Optional.ofNullable(identities.get(Objects.requireNonNull(key)));
    }

    @Override
    public synchronized java.util.List<ExternalIdentity> findByUser(UserId userId) {
        Objects.requireNonNull(userId);
        return identities.values().stream().filter(i -> i.userId().equals(userId)).toList();
    }

    @Override
    public synchronized void replaceStatus(ExternalIdentity expected, IdentityLinkStatus status) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(status);
        if (!identities.replace(expected.key(), expected, expected.withStatus(status))) {
            throw new UserDomainException(CONCURRENT_MODIFICATION);
        }
    }

    @Override
    public synchronized ExternalIdentity insertOrGet(ExternalIdentity identity) {
        Objects.requireNonNull(identity);
        ExternalIdentity existing = identities.get(identity.key());
        if (existing != null && (!existing.userId().equals(identity.userId())
                || existing.provider() != identity.provider())) {
            throw new UserDomainException(IDENTITY_CONFLICT);
        }
        User owner = users.get(identity.userId());
        if (owner == null) {
            throw new UserDomainException(USER_NOT_FOUND);
        }
        owner.requireActive();
        if (existing != null) {
            return existing;
        }
        identities.put(identity.key(), identity);
        return identity;
    }
}

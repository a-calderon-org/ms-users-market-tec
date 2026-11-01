package com.markettec.users.domain.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

public record User(UserId userId, AccountStatus status, UserProfile profile) {
    public User {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(profile);
        if (!userId.equals(profile.userId())) {
            throw new IllegalArgumentException("Profile must belong to the user");
        }
    }

    public static User create(UserId id, Email email, ProfileDetails details, URI photo, Instant now) {
        return new User(id, AccountStatus.ACTIVE,
                new UserProfile(id, details, email, photo, now, now, false));
    }

    public void requireActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new UserDomainException(UserDomainException.Reason.USER_DEACTIVATED);
        }
    }

    public User updateProfile(ProfileDetails details, Instant now) {
        requireActive();
        return new User(userId, status, profile.update(details, now));
    }

    public User verifyProfileEmail(Email verifiedEmail) {
        requireActive();
        return new User(userId, status, profile.verifyEmail(verifiedEmail));
    }

    public User deactivate() {
        return new User(userId, AccountStatus.DEACTIVATED, profile);
    }
}

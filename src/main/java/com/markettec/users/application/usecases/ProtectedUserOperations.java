package com.markettec.users.application.usecases;

import com.markettec.users.application.ports.in.UserUseCases;
import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.application.ports.in.VerifiedInternalToken;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.domain.model.*;
import java.time.Clock;
import java.util.Objects;

/** Internal entry point for existing own-account operations; no HTTP route is registered. */
public final class ProtectedUserOperations {
    private final InternalTokenVerifier jwt;
    private final SessionAuthority sessions;
    private final UserUseCases users;
    private final Clock clock;

    public ProtectedUserOperations(InternalTokenVerifier jwt, SessionAuthority sessions,
                                   UserUseCases users, Clock clock) {
        this.jwt = Objects.requireNonNull(jwt);
        this.sessions = Objects.requireNonNull(sessions);
        this.users = Objects.requireNonNull(users);
        this.clock = Objects.requireNonNull(clock);
    }

    /** A local JWT is sufficient for this immediate, non-streaming read. */
    public UserProfile getOwnProfile(String bearer, UserId target) {
        var token = authorize(bearer, target, Permission.READ_PROFILE);
        UserProfile result = users.getOwnProfile(token::userId, target);
        requireUnexpired(token);
        return result;
    }

    public UserProfile updateOwnProfile(String bearer, UserId target, ProfileDetails details) {
        return sensitive(bearer, target, Permission.UPDATE_PROFILE,
                token -> users.updateOwnProfile(token::userId, target, details));
    }

    public User deactivateUser(String bearer, UserId target) {
        return sensitive(bearer, target, Permission.DEACTIVATE_USER,
                token -> users.deactivateUser(token::userId, target));
    }

    public ExternalIdentity linkExternalIdentity(String bearer, UserId target,
                                                 VerifiedExternalIdentity identity) {
        return sensitive(bearer, target, Permission.LINK_IDENTITY,
                token -> users.linkExternalIdentity(token::userId, target, identity));
    }

    private <T> T sensitive(String bearer, UserId target, Permission permission,
                            java.util.function.Function<VerifiedInternalToken, T> work) {
        var token = authorize(bearer, target, permission);
        return sessions.withCurrentSession(token.sessionId(), token.userId(), () -> {
            requireUnexpired(token);
            T result = work.apply(token);
            requireUnexpired(token);
            return result;
        });
    }

    private VerifiedInternalToken authorize(String bearer, UserId target, Permission permission) {
        // Explicit operation allowlist: these permissions are granted only to the JWT subject
        // acting on its own account. No roles or permissions are accepted from request data.
        Objects.requireNonNull(permission);
        var token = jwt.verify(bearer);
        if (target == null || !token.userId().equals(target)) throw new SessionRejectedException();
        return token;
    }

    private void requireUnexpired(VerifiedInternalToken token) {
        if (!clock.instant().isBefore(token.expiresAt())) throw new SessionRejectedException();
    }

    private enum Permission { READ_PROFILE, UPDATE_PROFILE, DEACTIVATE_USER, LINK_IDENTITY }
}

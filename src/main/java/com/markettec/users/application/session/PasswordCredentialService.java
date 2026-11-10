package com.markettec.users.application.session;

import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.application.ports.out.ExternalIdentityRepository;
import com.markettec.users.application.ports.out.InternalTokenIssuer;
import com.markettec.users.application.ports.out.PasswordHasher;
import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.ports.out.UserTransactions;
import com.markettec.users.application.session.PasswordOperationException.Code;
import com.markettec.users.domain.model.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Institutional password credentials, bound to the account's exact institutional-email identity
 * ({@code ISSUER}, normalized address) and therefore to its stable {@code userId}.
 *
 * <ul>
 *   <li><b>Login</b>: verifies the password and issues the same session, JWT and cookies as every other method.
 *       Failures are uniform for unknown, ineligible, deactivated, Google-only and wrong-password cases, and the
 *       attempt throttle is keyed by an address digest so it behaves identically for accounts that do not exist.</li>
 *   <li><b>Initial password</b>: allowed only for an authenticated session holding a live, one-use setup grant,
 *       issued by a just-confirmed Magic Link for an account that has no password. No grant, no password.</li>
 *   <li><b>Change</b>: requires the current password; every other session of the account is revoked.</li>
 *   <li><b>Replacing a forgotten password</b> is deliberately not offered: a fresh mailbox link does not prove
 *       continuity of the historical account (B02), and no approved continuity proof exists yet.</li>
 * </ul>
 * Nothing here ever links, merges or creates identities.
 */
public final class PasswordCredentialService {
    public static final int MAX_INPUT = 256;
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(15);
    public static final Duration SETUP_GRANT_TTL = Duration.ofMinutes(10);

    private final UserTransactions tx;
    private final UserRepository users;
    private final ExternalIdentityRepository identities;
    private final PasswordCredentialRepository credentials;
    private final PasswordSetupGrantRepository grants;
    private final PasswordAttemptThrottle throttle;
    private final PasswordHasher hasher;
    private final SessionAuthority sessions;
    private final InternalTokenIssuer jwt;
    private final Clock clock;

    public PasswordCredentialService(UserTransactions tx, UserRepository users, ExternalIdentityRepository identities,
            PasswordCredentialRepository credentials, PasswordSetupGrantRepository grants,
            PasswordAttemptThrottle throttle, PasswordHasher hasher, SessionAuthority sessions,
            InternalTokenIssuer jwt, Clock clock) {
        this.tx = Objects.requireNonNull(tx);
        this.users = Objects.requireNonNull(users);
        this.identities = Objects.requireNonNull(identities);
        this.credentials = Objects.requireNonNull(credentials);
        this.grants = Objects.requireNonNull(grants);
        this.throttle = Objects.requireNonNull(throttle);
        this.hasher = Objects.requireNonNull(hasher);
        this.sessions = Objects.requireNonNull(sessions);
        this.jwt = Objects.requireNonNull(jwt);
        this.clock = Objects.requireNonNull(clock);
    }

    public record Status(boolean configured, boolean setupAvailable) { }

    private record Target(UserId userId, ExternalIdentityKey key, PasswordCredential credential) { }

    /** Normal sign-in. The same {@link GoogleLoginService.Result} as every other method; no Magic Link needed. */
    public GoogleLoginService.Result login(Email email, String password) {
        Objects.requireNonNull(email);
        String throttleKey = digest("login", email.normalized());
        requireNotLocked(throttleKey);
        if (password == null || password.isEmpty() || password.length() > MAX_INPUT) {
            hasher.burn("x");
            recordFailure(throttleKey);
            throw invalid();
        }
        Target target = tx.execute(() -> lookup(email));
        boolean verified;
        if (target == null) {
            hasher.burn(password);
            verified = false;
        } else {
            verified = hasher.matches(password, target.credential().hash());
        }
        if (!verified) {
            recordFailure(throttleKey);
            throw invalid();
        }
        return tx.execute(() -> {
            var current = credentials.findLocked(target.userId()).orElseThrow(PasswordCredentialService::invalid);
            // The password was replaced while this attempt was verifying: it no longer proves anything.
            if (!current.hash().equals(target.credential().hash())) throw invalid();
            throttle.clear(throttleKey);
            if (hasher.needsRehash(current.hash()))
                credentials.replace(current, current.replaceHash(hasher.hash(password), hasher.policyVersion(), now()));
            var issued = sessions.issue(sessions.createAuthenticated(new VerifiedExternalIdentity(
                    IdentityProvider.INSTITUTIONAL_EMAIL, target.key(), email)), jwt);
            return new GoogleLoginService.Result(issued.jwt(), issued.sessionId(), issued.userId(),
                    issued.sessionDeadline(), issued.refreshToken(), issued.csrfToken());
        });
    }

    /** Whether this account can use passwords and whether the first one may be set right now. */
    public Status status(String sessionId, UserId userId) {
        return sessions.withCurrentSession(sessionId, userId, () -> {
            if (accountEmail(userId) == null) return new Status(false, false);
            boolean configured = credentials.findLocked(userId).isPresent();
            return new Status(configured, !configured && grants.live(sessionId, userId, now()));
        });
    }

    /** First password for an account, authorized by the one-use grant of this very session. */
    public void setInitial(String sessionId, UserId userId, String password) {
        Email account = tx.execute(() -> {
            Email email = accountEmail(userId);
            if (email == null) throw new PasswordOperationException(Code.SETUP_NOT_AUTHORIZED);
            if (credentials.findLocked(userId).isPresent()) throw new PasswordOperationException(Code.PASSWORD_ALREADY_SET);
            if (!grants.live(sessionId, userId, now())) throw new PasswordOperationException(Code.SETUP_NOT_AUTHORIZED);
            return email;
        });
        PasswordPolicy.require(password, account);
        String hash = hasher.hash(password);
        sessions.withCurrentSession(sessionId, userId, () -> {
            if (accountEmail(userId) == null) throw new PasswordOperationException(Code.SETUP_NOT_AUTHORIZED);
            if (credentials.findLocked(userId).isPresent()) throw new PasswordOperationException(Code.PASSWORD_ALREADY_SET);
            Instant now = now();
            if (!grants.consume(sessionId, userId, now)) throw new PasswordOperationException(Code.SETUP_NOT_AUTHORIZED);
            credentials.insert(new PasswordCredential(userId, hash, hasher.policyVersion(), now, now, 0));
            return null;
        });
    }

    /** Change with the current password. Every other session of the account is revoked; this one survives. */
    public void change(String sessionId, UserId userId, String current, String replacement) {
        if (current == null || current.isEmpty() || current.length() > MAX_INPUT) throw invalid();
        Email account = tx.execute(() -> accountEmail(userId));
        if (account == null) throw new PasswordOperationException(Code.SETUP_NOT_AUTHORIZED);
        PasswordPolicy.require(replacement, account);
        if (replacement.equals(current)) throw new PasswordOperationException(Code.WEAK_PASSWORD);
        String throttleKey = digest("change", userId.value());
        requireNotLocked(throttleKey);
        PasswordCredential before = tx.execute(() ->
                credentials.findLocked(userId).orElseThrow(() -> new PasswordOperationException(Code.PASSWORD_NOT_SET)));
        if (!hasher.matches(current, before.hash())) {
            recordFailure(throttleKey);
            throw invalid();
        }
        String hash = hasher.hash(replacement);
        sessions.withCurrentSession(sessionId, userId, () -> {
            var locked = credentials.findLocked(userId)
                    .orElseThrow(() -> new PasswordOperationException(Code.PASSWORD_NOT_SET));
            if (locked.version() != before.version()) throw invalid(); // changed concurrently
            credentials.replace(locked, locked.replaceHash(hash, hasher.policyVersion(), now()));
            throttle.clear(throttleKey);
            sessions.revokeOtherSessions(userId, sessionId);
            return null;
        });
    }

    /** Active institutional identity of an active account on an eligible domain; otherwise null. */
    private Email accountEmail(UserId userId) {
        User user = users.findById(userId).orElse(null);
        if (user == null || user.status() != AccountStatus.ACTIVE) return null;
        Email email = user.profile().correo();
        if (!InstitutionalEmailProof.eligible(email)) return null;
        var link = identities.findByKey(new ExternalIdentityKey(EmailLoginService.ISSUER, email.normalized())).orElse(null);
        if (link == null || link.provider() != IdentityProvider.INSTITUTIONAL_EMAIL
                || link.status() != IdentityLinkStatus.ACTIVE || !link.userId().equals(userId)) return null;
        return email;
    }

    private Target lookup(Email email) {
        if (!InstitutionalEmailProof.eligible(email)) return null;
        var key = new ExternalIdentityKey(EmailLoginService.ISSUER, email.normalized());
        var link = identities.findByKey(key).orElse(null);
        if (link == null || link.provider() != IdentityProvider.INSTITUTIONAL_EMAIL
                || link.status() != IdentityLinkStatus.ACTIVE) return null;
        if (accountEmail(link.userId()) == null) return null;
        return credentials.findLocked(link.userId()).map(c -> new Target(link.userId(), key, c)).orElse(null);
    }

    private void requireNotLocked(String key) {
        Instant now = now();
        Instant until = tx.execute(() -> throttle.loadLocked(key).map(PasswordAttemptThrottle.State::lockedUntil).orElse(null));
        if (until != null && now.isBefore(until))
            throw new PasswordOperationException(Code.THROTTLED, Math.max(1, Duration.between(now, until).toSeconds()));
    }

    /** Committed on its own so the failure survives the exception that follows. */
    private void recordFailure(String key) {
        Instant now = now();
        tx.execute(() -> {
            var state = throttle.loadLocked(key).orElse(null);
            boolean stale = state == null || (!now.isBefore(state.windowStart().plus(WINDOW))
                    && !now.isBefore(state.lockedUntil()));
            int failures = stale ? 1 : state.failures() + 1;
            Instant start = stale ? now : state.windowStart();
            Instant locked = stale ? Instant.EPOCH : state.lockedUntil();
            if (failures >= MAX_FAILURES) locked = now.plus(LOCK);
            throttle.save(key, new PasswordAttemptThrottle.State(failures, start, locked));
            return null;
        });
    }

    private static PasswordOperationException invalid() { return new PasswordOperationException(Code.INVALID_CREDENTIALS); }

    private static String digest(String purpose, String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("mtec-password-" + purpose + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}

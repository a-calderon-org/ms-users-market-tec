package com.markettec.users.application.session;

import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.application.ports.out.ExternalIdentityRepository;
import com.markettec.users.application.ports.out.InternalTokenIssuer;
import com.markettec.users.application.ports.out.UserIdGenerator;
import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.ports.out.UserTransactions;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Independent institutional Magic Link authentication, repeatable for the same account. The account is
 * resolved only by the exact (issuer, normalized address) identity key; an address already used by another
 * account (e.g. Google) never merges.
 */
public final class EmailLoginService {
    public static final String ISSUER = "urn:markettec:institutional-email";
    static final Duration TTL = Duration.ofMinutes(10);
    static final Duration THROTTLE_WINDOW = Duration.ofMinutes(15);
    static final int MAX_REQUESTS_PER_WINDOW = 3;

    private final EmailLoginAttemptRepository attempts;
    private final UserTransactions tx;
    private final UserRepository users;
    private final ExternalIdentityRepository identities;
    private final PendingGoogleRegistrationRepository emails;
    private final UserIdGenerator ids;
    private final SessionAuthority sessions;
    private final InternalTokenIssuer jwt;
    private final InstitutionalLinkSender sender;
    private final Clock clock;
    private final SecureRandom random;
    /** Optional password feature: both null when passwords are disabled, which keeps the plain Magic Link flow. */
    private final PasswordCredentialRepository credentials;
    private final PasswordSetupGrantRepository grants;
    /** Optional: delivers recovery links (its own link base); null means recovery is unavailable. */
    private final InstitutionalLinkSender recoverySender;

    public EmailLoginService(EmailLoginAttemptRepository attempts, UserTransactions tx, UserRepository users,
            ExternalIdentityRepository identities, PendingGoogleRegistrationRepository emails,
            UserIdGenerator ids, SessionAuthority sessions, InternalTokenIssuer jwt,
            InstitutionalLinkSender sender, Clock clock, SecureRandom random) {
        this(attempts, tx, users, identities, emails, ids, sessions, jwt, sender, clock, random, null, null, null);
    }

    public EmailLoginService(EmailLoginAttemptRepository attempts, UserTransactions tx, UserRepository users,
            ExternalIdentityRepository identities, PendingGoogleRegistrationRepository emails,
            UserIdGenerator ids, SessionAuthority sessions, InternalTokenIssuer jwt,
            InstitutionalLinkSender sender, Clock clock, SecureRandom random,
            PasswordCredentialRepository credentials, PasswordSetupGrantRepository grants,
            InstitutionalLinkSender recoverySender) {
        if ((credentials == null) != (grants == null)) throw new IllegalArgumentException("Password feature is all or nothing");
        this.credentials = credentials;
        this.grants = grants;
        this.recoverySender = recoverySender;
        this.attempts = Objects.requireNonNull(attempts);
        this.tx = Objects.requireNonNull(tx);
        this.users = Objects.requireNonNull(users);
        this.identities = Objects.requireNonNull(identities);
        this.emails = Objects.requireNonNull(emails);
        this.ids = Objects.requireNonNull(ids);
        this.sessions = Objects.requireNonNull(sessions);
        this.jwt = Objects.requireNonNull(jwt);
        this.sender = Objects.requireNonNull(sender);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * Binds the link to the requesting browser: the returned secret belongs in an HttpOnly cookie.
     * Always returns a browser secret, whether or not a link was sent (throttled requests are silent),
     * so callers cannot learn anything about accounts or rate state. Non-institutional domains are rejected.
     */
    public String request(Email email) {
        return request(email, EmailLoginAttempt.Purpose.LOGIN);
    }

    /** Whether password recovery links can be delivered at all (configuration, not account state). */
    public boolean recoveryAvailable() { return recoverySender != null && credentials != null; }

    /**
     * A recovery link is only sent for an existing, active institutional identity; for any other address the
     * request is acknowledged identically and nothing is sent or stored. The link proves control of the mailbox
     * and nothing more: see {@link #confirm} for what it may and may not do.
     */
    public String requestRecovery(Email email) {
        if (!recoveryAvailable()) throw new IllegalStateException("Password recovery is not configured");
        return request(email, EmailLoginAttempt.Purpose.RECOVERY);
    }

    private String request(Email email, EmailLoginAttempt.Purpose purpose) {
        if (!InstitutionalEmailProof.eligible(email)) throw new SessionRejectedException();
        Instant now = now();
        String browser = randomHex(32);
        String[] issued = tx.execute(() -> {
            if (attempts.countSince(email.normalized(), now.minus(THROTTLE_WINDOW)) >= MAX_REQUESTS_PER_WINDOW)
                return null;
            if (purpose == EmailLoginAttempt.Purpose.RECOVERY && !recoverable(email)) return null;
            String id = randomHex(16);
            String secret = randomHex(32);
            attempts.insert(new EmailLoginAttempt(id, email, digest(email, id, secret), sha256(browser),
                    now, now.plus(TTL), 0, false, 0, purpose));
            return new String[]{id, secret};
        });
        if (issued == null) return browser;
        if (purpose != EmailLoginAttempt.Purpose.RECOVERY) {
            sender.send(email, issued[0], issued[1]);
            return browser;
        }
        try {
            recoverySender.send(email, issued[0], issued[1]);
        } catch (EmailDeliveryException undelivered) {
            // Only existing accounts get a recovery link, so a delivery failure must not change the answer:
            // the request is acknowledged exactly like one for an unknown address. The sender audits it.
        }
        return browser;
    }

    private boolean recoverable(Email email) {
        var link = identities.findByKey(new ExternalIdentityKey(ISSUER, email.normalized())).orElse(null);
        if (link == null || link.provider() != IdentityProvider.INSTITUTIONAL_EMAIL
                || link.status() != IdentityLinkStatus.ACTIVE) return false;
        return users.findById(link.userId()).map(u -> u.status() == AccountStatus.ACTIVE).orElse(false);
    }

    private enum Kind { OK, CONSUMED, EXPIRED, REJECTED, CONTINUITY }
    private record Outcome(Kind kind, GoogleLoginService.Result result) { }

    /**
     * Explicit POST confirmation from the requesting browser. Consumption and session issue commit together.
     * <ul>
     *   <li>New institutional address: creates the account, its institutional identity and a session.</li>
     *   <li>Existing institutional identity (exact issuer/address key): a new session for the same user.</li>
     *   <li>Address owned only by another account (e.g. Google): never merged on address equality; the link
     *       is spent and CONTINUITY_REQUIRED is returned without credentials.</li>
     *   <li>Recovery links ({@code RECOVERY}) never create accounts and never sign in an account that already
     *       has a password: CONTINUITY_REQUIRED, link spent.</li>
     * </ul>
     * The profile-address range lock is taken before the identity lookup, so concurrent first confirmations
     * for one address serialize: the loser observes the winner's identity and signs in to the same user.
     */
    public GoogleLoginService.Result confirm(String attemptId, String browserSecret, String token) {
        if (attemptId == null || !attemptId.matches("[0-9a-f]{32}") || token == null || !token.matches("[0-9a-f]{64}")
                || browserSecret == null || !browserSecret.matches("[0-9a-f]{64}"))
            throw new SessionRejectedException();
        Outcome outcome = tx.execute(() -> {
            var attempt = attempts.loadLocked(attemptId).orElse(null);
            if (attempt == null) return new Outcome(Kind.REJECTED, null);
            Instant now = now();
            // A foreign browser changes nothing and learns nothing: no failure count, no consumption.
            if (!equal(attempt.browserHash(), sha256(browserSecret))) return new Outcome(Kind.REJECTED, null);
            if (attempt.consumed()) return new Outcome(Kind.CONSUMED, null);
            if (!now.isBefore(attempt.expiresAt())) return new Outcome(Kind.EXPIRED, null);
            if (attempt.failures() >= EmailLoginAttempt.MAX_FAILURES) return new Outcome(Kind.REJECTED, null);
            // Only the requesting browser can burn attempts with a wrong token.
            if (!equal(attempt.verifier(), digest(attempt.recipient(), attemptId, token))) {
                attempts.replace(attempt, attempt.failed()); // committed: the failure is returned, not thrown
                return new Outcome(Kind.REJECTED, null);
            }
            attempts.replace(attempt, attempt.consume());
            var email = attempt.recipient();
            var key = new ExternalIdentityKey(ISSUER, email.normalized());
            boolean addressInUse = emails.historicalEmailExists(email.normalized()); // range lock first
            var existing = identities.findByKey(key);
            if (existing.isPresent()) {
                var linked = existing.get();
                if (linked.provider() != IdentityProvider.INSTITUTIONAL_EMAIL
                        || linked.status() != IdentityLinkStatus.ACTIVE)
                    return new Outcome(Kind.REJECTED, null);
                // B02: control of the mailbox does not prove continuity of an account that already holds a
                // password (the address may have been reassigned). A recovery link must never replace or bypass
                // that credential; the link is spent and nothing is issued.
                if (attempt.purpose() == EmailLoginAttempt.Purpose.RECOVERY && hasPassword(linked.userId()))
                    return new Outcome(Kind.CONTINUITY, null);
                return new Outcome(Kind.OK, withSetupGrant(issue(new VerifiedExternalIdentity(
                        IdentityProvider.INSTITUTIONAL_EMAIL, key, email)), now));
            }
            // Recovery never creates accounts: registration is the ordinary link.
            if (attempt.purpose() == EmailLoginAttempt.Purpose.RECOVERY) return new Outcome(Kind.REJECTED, null);
            if (addressInUse) return new Outcome(Kind.CONTINUITY, null);
            return new Outcome(Kind.OK, withSetupGrant(createAndIssue(email, key, now), now));
        });
        return switch (outcome.kind()) {
            case OK -> outcome.result();
            case CONSUMED -> throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_CONSUMED);
            case EXPIRED -> throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_EXPIRED);
            case CONTINUITY -> throw new SessionRejectedException(SessionRejectedException.Reason.CONTINUITY_REQUIRED);
            case REJECTED -> throw new SessionRejectedException();
        };
    }

    private boolean hasPassword(UserId userId) {
        return credentials != null && credentials.findLocked(userId).isPresent();
    }

    /**
     * A confirmed link for an account with no password authorizes setting its first password from this very
     * session, once, for a short time. Accounts that already have one get no grant (replacing it needs the
     * current password, or a continuity proof that is not approved yet).
     */
    private GoogleLoginService.Result withSetupGrant(GoogleLoginService.Result result, Instant now) {
        if (grants != null && !hasPassword(new UserId(result.userId())))
            grants.issue(result.sessionId(), new UserId(result.userId()), now, now.plus(PasswordCredentialService.SETUP_GRANT_TTL));
        return result;
    }

    private GoogleLoginService.Result createAndIssue(Email email, ExternalIdentityKey key, Instant now) {
        var userId = ids.nextId();
        var user = User.create(userId, email, new ProfileDetails(userId.value(), "Usuario", ""),
                URI.create("about:blank"), now);
        users.insert(user);
        users.replace(user, user.verifyProfileEmail(email));
        identities.insertOrGet(new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL, key, userId,
                email, IdentityLinkStatus.ACTIVE));
        return issue(new VerifiedExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL, key, email));
    }

    /** Session for an existing, active institutional identity (SessionAuthority re-checks link and user). */
    private GoogleLoginService.Result issue(VerifiedExternalIdentity evidence) {
        var issued = sessions.issue(sessions.createAuthenticated(evidence), jwt);
        return new GoogleLoginService.Result(issued.jwt(), issued.sessionId(), issued.userId(),
                issued.sessionDeadline(), issued.refreshToken(), issued.csrfToken());
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String digest(Email recipient, String attemptId, String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{"MarketTec magic link v1", recipient.normalized(), attemptId, secret}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                sha.update((byte) (bytes.length >>> 8));
                sha.update((byte) bytes.length);
                sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean equal(String a, String b) {
        return MessageDigest.isEqual(HexFormat.of().parseHex(a), HexFormat.of().parseHex(b));
    }

    private String randomHex(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}

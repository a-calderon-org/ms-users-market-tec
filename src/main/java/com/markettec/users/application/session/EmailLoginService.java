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
 * Independent institutional Magic Link authentication. The account is resolved only by the exact
 * (issuer, normalized address) identity key; an address already used by another account never merges.
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

    public EmailLoginService(EmailLoginAttemptRepository attempts, UserTransactions tx, UserRepository users,
            ExternalIdentityRepository identities, PendingGoogleRegistrationRepository emails,
            UserIdGenerator ids, SessionAuthority sessions, InternalTokenIssuer jwt,
            InstitutionalLinkSender sender, Clock clock, SecureRandom random) {
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
     * Returns normally whether or not a link was sent (throttled requests are silent), so callers
     * cannot learn anything about accounts or rate state. Non-institutional domains are rejected.
     */
    public void request(Email email) {
        if (!InstitutionalEmailProof.eligible(email)) throw new SessionRejectedException();
        Instant now = now();
        String[] issued = tx.execute(() -> {
            if (attempts.countSince(email.normalized(), now.minus(THROTTLE_WINDOW)) >= MAX_REQUESTS_PER_WINDOW)
                return null;
            String id = randomHex(16);
            String secret = randomHex(32);
            attempts.insert(new EmailLoginAttempt(id, email, digest(email, id, secret), now, now.plus(TTL), 0, false, 0));
            return new String[]{id, secret};
        });
        if (issued != null) sender.send(email, issued[0], issued[1]);
    }

    private enum Kind { OK, CONSUMED, EXPIRED, REJECTED }
    private record Outcome(Kind kind, GoogleLoginService.Result result) { }

    /** Explicit POST confirmation. Consumption, account resolution and session issue commit together. */
    public GoogleLoginService.Result confirm(String attemptId, String secret) {
        if (attemptId == null || !attemptId.matches("[0-9a-f]{32}") || secret == null || !secret.matches("[0-9a-f]{64}"))
            throw new SessionRejectedException();
        Outcome outcome = tx.execute(() -> {
            var attempt = attempts.loadLocked(attemptId).orElse(null);
            if (attempt == null) return new Outcome(Kind.REJECTED, null);
            Instant now = now();
            if (attempt.consumed()) return new Outcome(Kind.CONSUMED, null);
            if (!now.isBefore(attempt.expiresAt())) return new Outcome(Kind.EXPIRED, null);
            if (attempt.failures() >= EmailLoginAttempt.MAX_FAILURES) return new Outcome(Kind.REJECTED, null);
            if (!equal(attempt.verifier(), digest(attempt.recipient(), attemptId, secret))) {
                attempts.replace(attempt, attempt.failed()); // committed: the failure is returned, not thrown
                return new Outcome(Kind.REJECTED, null);
            }
            attempts.replace(attempt, attempt.consume());
            return new Outcome(Kind.OK, resolveAndIssue(attempt.recipient(), now));
        });
        return switch (outcome.kind()) {
            case OK -> outcome.result();
            case CONSUMED -> throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_CONSUMED);
            case EXPIRED -> throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_EXPIRED);
            case REJECTED -> throw new SessionRejectedException();
        };
    }

    private GoogleLoginService.Result resolveAndIssue(Email email, Instant now) {
        var key = new ExternalIdentityKey(ISSUER, email.normalized());
        var linked = identities.findByKey(key);
        if (linked.isPresent()) {
            if (linked.get().provider() != IdentityProvider.INSTITUTIONAL_EMAIL
                    || linked.get().status() != IdentityLinkStatus.ACTIVE) throw new SessionRejectedException();
        } else {
            // Never attach to an account that merely shares the address.
            if (emails.historicalEmailExists(email.normalized()))
                throw new SessionRejectedException(SessionRejectedException.Reason.IDENTITY_CONFLICT);
            var userId = ids.nextId();
            var user = User.create(userId, email, new ProfileDetails(userId.value(), "Usuario", ""),
                    URI.create("about:blank"), now);
            users.insert(user);
            users.replace(user, user.verifyProfileEmail(email));
            identities.insertOrGet(new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL, key, userId,
                    email, IdentityLinkStatus.ACTIVE));
        }
        var evidence = new VerifiedExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL, key, email);
        var issued = sessions.issue(sessions.createAuthenticated(evidence), jwt);
        return new GoogleLoginService.Result(issued.jwt(), issued.sessionId(), issued.userId(),
                issued.sessionDeadline(), issued.refreshToken(), issued.csrfToken());
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

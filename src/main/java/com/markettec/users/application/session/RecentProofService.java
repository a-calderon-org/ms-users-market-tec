package com.markettec.users.application.session;

import com.markettec.users.application.ports.out.*;
import com.markettec.users.domain.model.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/** Recent-authentication proofs for an already authenticated session. Never creates sessions. */
public final class RecentProofService {
    /** Proof of control of the method the account already uses; target is the provider being added. */
    public static final String LINK_CURRENT = "LINK_IDENTITY_CURRENT";
    /** Proof of control of the method being added; target names the exact identity (email) or provider. */
    public static final String LINK_NEW = "LINK_IDENTITY_NEW";
    public static final String EMAIL_TARGET_PREFIX = "INSTITUTIONAL_EMAIL:";

    private final SessionAuthority sessions;
    private final RecentAuthenticationAuthority recent;
    private final RecentAttemptRepository attempts;
    private final EmailProofChallengeRepository challenges;
    private final UserRepository users;
    private final ExternalIdentityRepository identities;
    private final GoogleIdentityVerifier google;
    private final String googleAudience;
    private final InstitutionalLinkSender sender;
    private final Clock clock;
    private final SecureRandom random;

    public RecentProofService(SessionAuthority sessions, RecentAuthenticationAuthority recent,
            RecentAttemptRepository attempts, EmailProofChallengeRepository challenges, UserRepository users,
            ExternalIdentityRepository identities, GoogleIdentityVerifier google, String googleAudience,
            InstitutionalLinkSender sender, Clock clock, SecureRandom random) {
        this.sessions = Objects.requireNonNull(sessions);
        this.recent = Objects.requireNonNull(recent);
        this.attempts = Objects.requireNonNull(attempts);
        this.challenges = challenges;
        this.users = Objects.requireNonNull(users);
        this.identities = Objects.requireNonNull(identities);
        this.google = google;
        this.googleAudience = googleAudience;
        this.sender = sender;
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    public boolean googleAvailable() { return google != null && googleAudience != null && !googleAudience.isBlank(); }
    public boolean emailAvailable() { return challenges != null && sender != null; }

    /** Allowlisted operation/method/target combinations only. */
    public RecentAttempt begin(String sessionId, UserId userId, RecentAttempt.Method method,
                               String operation, String target) {
        if (method == null || operation == null || target == null) throw new SessionRejectedException();
        boolean ok = switch (operation) {
            case "DEACTIVATE_USER" -> method == RecentAttempt.Method.INSTITUTIONAL_EMAIL
                    && userId.value().equals(target);
            // Adding Google is authorized by the account's email; adding email by its Google.
            case LINK_CURRENT -> ("GOOGLE".equals(target) && method == RecentAttempt.Method.INSTITUTIONAL_EMAIL)
                    || ("INSTITUTIONAL_EMAIL".equals(target) && method == RecentAttempt.Method.GOOGLE);
            case LINK_NEW -> ("GOOGLE".equals(target) && method == RecentAttempt.Method.GOOGLE)
                    || (method == RecentAttempt.Method.INSTITUTIONAL_EMAIL
                        && target.equals(EMAIL_TARGET_PREFIX + ownEmail(userId).normalized()));
            default -> false;
        };
        if (!ok) throw new SessionRejectedException();
        return recent.begin(sessionId, userId, method, operation, target);
    }

    private Email ownEmail(UserId userId) { return users.findById(userId).map(u -> u.profile().correo())
            .orElseThrow(SessionRejectedException::new); }

    /** Issues the one-use link to the account's own verified institutional address. */
    public void requestEmail(String sessionId, UserId userId, String attemptId) {
        if (!emailAvailable()) throw new IllegalStateException("Email proofs are not configured");
        Object[] delivery = sessions.withCurrentSession(sessionId, userId, () -> {
            var attempt = bound(attemptId, sessionId, userId, RecentAttempt.Method.INSTITUTIONAL_EMAIL);
            Email recipient = recipient(userId);
            requireEmailTarget(attempt, recipient);
            var issued = InstitutionalEmailProof.issue(recipient, attempt, now(), random);
            challenges.insert(attemptId, issued.verifier());
            return new Object[]{recipient, issued.secret()};
        });
        sender.send((Email) delivery[0], attemptId, (String) delivery[1]);
    }

    public void confirmEmail(String sessionId, UserId userId, String attemptId, String secret) {
        if (!emailAvailable()) throw new IllegalStateException("Email proofs are not configured");
        sessions.withCurrentSession(sessionId, userId, () -> {
            var attempt = bound(attemptId, sessionId, userId, RecentAttempt.Method.INSTITUTIONAL_EMAIL);
            String verifier = challenges.loadOpenLocked(attemptId).orElseThrow(SessionRejectedException::new);
            requireEmailTarget(attempt, recipient(userId));
            if (!InstitutionalEmailProof.matches(recipient(userId), attempt, verifier, secret, now()))
                throw new SessionRejectedException();
            challenges.markConsumed(attemptId);
            record(attempt, userId, sessionId);
            return null;
        });
    }

    /** A fresh Google ID token bound to this attempt's nonce, owned by the same account. */
    public void proveGoogle(String sessionId, UserId userId, String attemptId, String credential) {
        if (!googleAvailable()) throw new IllegalStateException("Google proofs are not configured");
        if (credential == null || credential.isBlank() || credential.length() > 16_384)
            throw new SessionRejectedException();
        sessions.withCurrentSession(sessionId, userId, () -> {
            var attempt = bound(attemptId, sessionId, userId, RecentAttempt.Method.GOOGLE);
            // A Google proof of the CURRENT method; proving a new Google identity happens at link time.
            if (!LINK_CURRENT.equals(attempt.operation())) throw new SessionRejectedException();
            var verified = google.verify(credential, new GoogleVerificationContext(googleAudience, attempt.nonce()));
            if (verified == null || !googleAudience.equals(verified.audience())) throw new SessionRejectedException();
            var linked = identities.findByKey(new ExternalIdentityKey(verified.issuer(), verified.subject()))
                    .orElseThrow(SessionRejectedException::new);
            if (linked.provider() != IdentityProvider.GOOGLE || linked.status() != IdentityLinkStatus.ACTIVE
                    || !linked.userId().equals(userId)) throw new SessionRejectedException();
            record(attempt, userId, sessionId);
            return null;
        });
    }

    /** An email proof for a new identity is valid only for the address named in its target. */
    private static void requireEmailTarget(RecentAttempt attempt, Email recipient) {
        if (LINK_NEW.equals(attempt.operation())
                && !attempt.target().equals(EMAIL_TARGET_PREFIX + recipient.normalized()))
            throw new SessionRejectedException();
    }

    private void record(RecentAttempt attempt, UserId userId, String sessionId) {
        recent.recordVerified(new TrustedRecentEvidence(userId, sessionId, attempt.attemptId(),
                attempt.method(), attempt.operation(), attempt.target(), attempt.nonce(), now()));
    }

    private RecentAttempt bound(String attemptId, String sessionId, UserId userId, RecentAttempt.Method method) {
        var attempt = attempts.loadLocked(attemptId).orElseThrow(SessionRejectedException::new);
        if (!attempt.userId().equals(userId) || !attempt.sessionId().equals(sessionId)
                || attempt.method() != method) throw new SessionRejectedException();
        return attempt;
    }

    private Email recipient(UserId userId) {
        User user = users.findById(userId).orElseThrow(SessionRejectedException::new);
        user.requireActive();
        return user.profile().correo();
    }

    private java.time.Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}

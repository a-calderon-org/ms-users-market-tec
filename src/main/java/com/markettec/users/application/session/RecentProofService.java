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
    public static final String LINK_OPERATION = "LINK_IDENTITY";
    public static final Set<String> LINK_TARGETS = Set.of("GOOGLE", "INSTITUTIONAL_EMAIL");

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
        boolean ok = switch (method) {
            case INSTITUTIONAL_EMAIL -> (LINK_OPERATION.equals(operation) && "GOOGLE".equals(target))
                    || ("DEACTIVATE_USER".equals(operation) && userId.value().equals(target));
            case GOOGLE -> LINK_OPERATION.equals(operation) && "INSTITUTIONAL_EMAIL".equals(target);
            case PASSKEY -> false;
        };
        if (!ok) throw new SessionRejectedException();
        return recent.begin(sessionId, userId, method, operation, target);
    }

    /** Issues the one-use link to the account's own verified institutional address. */
    public void requestEmail(String sessionId, UserId userId, String attemptId) {
        if (!emailAvailable()) throw new IllegalStateException("Email proofs are not configured");
        Object[] delivery = sessions.withCurrentSession(sessionId, userId, () -> {
            var attempt = bound(attemptId, sessionId, userId, RecentAttempt.Method.INSTITUTIONAL_EMAIL);
            Email recipient = recipient(userId);
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

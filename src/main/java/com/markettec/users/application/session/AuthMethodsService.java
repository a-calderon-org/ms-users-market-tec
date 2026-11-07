package com.markettec.users.application.session;

import com.markettec.users.application.ports.out.*;
import com.markettec.users.domain.model.*;
import java.util.List;
import java.util.Objects;

/**
 * Lists and explicitly links authentication methods. Linking consumes a single-use recent proof
 * bound to this session, account and provider; no link is ever inferred from a shared email.
 */
public final class AuthMethodsService {
    private final SessionAuthority sessions;
    private final RecentAuthenticationAuthority recent;
    private final ExternalIdentityRepository identities;
    private final UserRepository users;
    private final ProvenIdentityRepository proven;
    private final GoogleIdentityVerifier google;
    private final String googleAudience;

    public AuthMethodsService(SessionAuthority sessions, RecentAuthenticationAuthority recent,
            ExternalIdentityRepository identities, UserRepository users,
            GoogleIdentityVerifier google, String googleAudience, ProvenIdentityRepository proven) {
        this.proven = Objects.requireNonNull(proven);
        this.sessions = Objects.requireNonNull(sessions);
        this.recent = Objects.requireNonNull(recent);
        this.identities = Objects.requireNonNull(identities);
        this.users = Objects.requireNonNull(users);
        this.google = google;
        this.googleAudience = googleAudience;
    }

    public List<ExternalIdentity> list(String sessionId, UserId userId) {
        return sessions.withCurrentSession(sessionId, userId, () -> identities.findByUser(userId));
    }

    /**
     * Requires two independent, single-use recent proofs consumed atomically with the link:
     * the CURRENT method (email when adding Google, Google when adding email) and the NEW one
     * (a nonce-bound Google credential, or an email link to the exact address being added).
     * A verified profile address alone is never sufficient.
     */
    public ExternalIdentity link(String sessionId, UserId userId, IdentityProvider provider,
                                 String currentAttemptId, String newAttemptId, String credential) {
        boolean addGoogle = provider == IdentityProvider.GOOGLE;
        if (Objects.equals(currentAttemptId, newAttemptId)) throw new SessionRejectedException();
        if (addGoogle && (google == null || googleAudience == null || googleAudience.isBlank()
                || credential == null || credential.isBlank() || credential.length() > 16_384))
            throw new SessionRejectedException();
        var currentMethod = addGoogle ? RecentAttempt.Method.INSTITUTIONAL_EMAIL : RecentAttempt.Method.GOOGLE;
        var currentProvider = addGoogle ? IdentityProvider.INSTITUTIONAL_EMAIL : IdentityProvider.GOOGLE;
        return recent.consumeWith(sessionId, userId, currentAttemptId, currentMethod,
                RecentProofService.LINK_CURRENT, provider.name(), current -> {
            User user = users.findById(userId).orElseThrow(SessionRejectedException::new);
            user.requireActive();
            // Revalidate the exact identity the current proof demonstrated: same key, still this
            // account's, same provider, still ACTIVE (not suspended, revoked or reassigned).
            var provenKey = proven.find(currentAttemptId).orElseThrow(SessionRejectedException::new);
            var held = identities.findByKey(provenKey).orElseThrow(SessionRejectedException::new);
            if (held.provider() != currentProvider || !held.userId().equals(userId)
                    || held.status() != IdentityLinkStatus.ACTIVE) throw new SessionRejectedException();
            ExternalIdentity candidate;
            RecentAttempt newProof;
            if (addGoogle) {
                // The new-method proof is the credential itself, bound to its own attempt nonce.
                var fresh = recent.attempt(newAttemptId).orElseThrow(SessionRejectedException::new);
                var verified = google.verify(credential, new GoogleVerificationContext(googleAudience, fresh.nonce()));
                if (verified == null || !googleAudience.equals(verified.audience())
                        || verified.verifiedEmail() == null
                        || !InstitutionalEmailProof.eligible(verified.verifiedEmail()))
                    throw new SessionRejectedException();
                recent.recordVerifiedFor(fresh, userId, sessionId);
                newProof = recent.attempt(newAttemptId).orElseThrow(SessionRejectedException::new);
                recent.consumeWith(sessionId, userId, newAttemptId, RecentAttempt.Method.GOOGLE,
                        RecentProofService.LINK_NEW, "GOOGLE", a -> null);
                candidate = new ExternalIdentity(IdentityProvider.GOOGLE,
                        new ExternalIdentityKey(verified.issuer(), verified.subject()), userId,
                        verified.verifiedEmail(), IdentityLinkStatus.ACTIVE);
            } else {
                Email own = user.profile().correo();
                if (!InstitutionalEmailProof.eligible(own)) throw new SessionRejectedException();
                newProof = recent.attempt(newAttemptId).orElseThrow(SessionRejectedException::new);
                recent.consumeWith(sessionId, userId, newAttemptId, RecentAttempt.Method.INSTITUTIONAL_EMAIL,
                        RecentProofService.LINK_NEW, RecentProofService.EMAIL_TARGET_PREFIX + own.normalized(),
                        a -> null);
                candidate = new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL,
                        new ExternalIdentityKey(EmailLoginService.ISSUER, own.normalized()), userId, own,
                        IdentityLinkStatus.ACTIVE);
            }
            ExternalIdentity stored = identities.insertOrGet(candidate);
            // Both proofs must still be inside their window at the end; otherwise everything rolls back.
            recent.requireFresh(current);
            recent.requireFresh(newProof);
            return stored;
        });
    }
}

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
    private final GoogleIdentityVerifier google;
    private final String googleAudience;

    public AuthMethodsService(SessionAuthority sessions, RecentAuthenticationAuthority recent,
            ExternalIdentityRepository identities, UserRepository users,
            GoogleIdentityVerifier google, String googleAudience) {
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
     * Google: needs a verified email proof (attempt target GOOGLE) and a Google ID token bound to the attempt nonce.
     * Institutional email: needs a verified Google proof (attempt target INSTITUTIONAL_EMAIL); links the
     * account's own verified institutional profile address.
     */
    public ExternalIdentity link(String sessionId, UserId userId, IdentityProvider provider,
                                 String attemptId, String credential) {
        boolean isGoogle = provider == IdentityProvider.GOOGLE;
        if (isGoogle && (google == null || googleAudience == null || googleAudience.isBlank()
                || credential == null || credential.isBlank() || credential.length() > 16_384))
            throw new SessionRejectedException();
        var method = isGoogle ? RecentAttempt.Method.INSTITUTIONAL_EMAIL : RecentAttempt.Method.GOOGLE;
        return recent.consumeWith(sessionId, userId, attemptId, method, RecentProofService.LINK_OPERATION,
                provider.name(), attempt -> {
            User user = users.findById(userId).orElseThrow(SessionRejectedException::new);
            user.requireActive();
            ExternalIdentity candidate;
            if (isGoogle) {
                var verified = google.verify(credential, new GoogleVerificationContext(googleAudience, attempt.nonce()));
                if (verified == null || !googleAudience.equals(verified.audience())
                        || verified.verifiedEmail() == null
                        || !InstitutionalEmailProof.eligible(verified.verifiedEmail()))
                    throw new SessionRejectedException();
                candidate = new ExternalIdentity(IdentityProvider.GOOGLE,
                        new ExternalIdentityKey(verified.issuer(), verified.subject()), userId,
                        verified.verifiedEmail(), IdentityLinkStatus.ACTIVE);
            } else {
                Email own = user.profile().correo();
                if (!user.profile().verificado() || !InstitutionalEmailProof.eligible(own))
                    throw new SessionRejectedException();
                candidate = new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL,
                        new ExternalIdentityKey(EmailLoginService.ISSUER, own.normalized()), userId, own,
                        IdentityLinkStatus.ACTIVE);
            }
            return identities.insertOrGet(candidate);
        });
    }
}

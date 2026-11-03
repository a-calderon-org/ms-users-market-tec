package com.markettec.users.application.session;

import com.markettec.users.application.ports.out.*;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/** Explicit internal enrollment of a previously unlinked Google identity. */
public final class GoogleRegistrationService {
    private final GoogleLoginIntentRepository intents;
    private final UserTransactions transactions;
    private final GoogleIdentityVerifier verifier;
    private final UserRepository users;
    private final ExternalIdentityRepository identities;
    private final UserIdGenerator ids;
    private final SessionAuthority sessions;
    private final InternalTokenIssuer jwt;
    private final String audience;
    private final Clock clock;
    private final SecureRandom random;

    public GoogleRegistrationService(GoogleLoginIntentRepository intents, UserTransactions transactions,
            GoogleIdentityVerifier verifier, UserRepository users, ExternalIdentityRepository identities,
            UserIdGenerator ids, SessionAuthority sessions, InternalTokenIssuer jwt,
            String audience, Clock clock, SecureRandom random) {
        this.intents = Objects.requireNonNull(intents);
        this.transactions = Objects.requireNonNull(transactions);
        this.verifier = Objects.requireNonNull(verifier);
        this.users = Objects.requireNonNull(users);
        this.identities = Objects.requireNonNull(identities);
        this.ids = Objects.requireNonNull(ids);
        this.sessions = Objects.requireNonNull(sessions);
        this.jwt = Objects.requireNonNull(jwt);
        if (audience == null || audience.isBlank() || audience.length() > 256)
            throw new IllegalArgumentException("Google audience is required");
        this.audience = audience;
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    public GoogleLoginService.Challenge begin() {
        var now = now();
        var secret = randomHex(32);
        var intent = new GoogleLoginIntent(randomHex(16), randomHex(32), digest(secret),
                now, now.plusSeconds(300), false, 0, GoogleLoginIntent.Purpose.REGISTRATION);
        transactions.execute(() -> { intents.insert(intent); return null; });
        return new GoogleLoginService.Challenge(intent.attemptId(), intent.nonce(), secret, intent.expiresAt());
    }

    /** The account and link commit together with intent consumption; session creation follows commit. */
    public GoogleLoginService.Result complete(String attemptId, String browserSecret, String idToken) {
        if (attemptId == null || !attemptId.matches("[0-9a-f]{32}")
                || browserSecret == null || !browserSecret.matches("[0-9a-f]{64}")
                || idToken == null || idToken.isBlank() || idToken.length() > 16_384)
            throw new SessionRejectedException();
        VerifiedGoogleIdentity verified = transactions.execute(() -> {
            var intent = intents.loadLocked(attemptId).orElseThrow(SessionRejectedException::new);
            if (intent.purpose() != GoogleLoginIntent.Purpose.REGISTRATION || !intent.validAt(now())
                    || !MessageDigest.isEqual(HexFormat.of().parseHex(intent.browserHash()),
                            HexFormat.of().parseHex(digest(browserSecret))))
                throw new SessionRejectedException();
            var evidence = verifier.verify(idToken, new GoogleVerificationContext(audience, intent.nonce()));
            if (!intent.validAt(now()) || evidence == null || evidence.verifiedEmail() == null
                    || !audience.equals(evidence.audience()))
                throw new SessionRejectedException();
            var key = new ExternalIdentityKey(evidence.issuer(), evidence.subject());
            if (identities.findByKey(key).isPresent()) throw new SessionRejectedException();
            var id = ids.nextId();
            // A Google assertion supplies an address for the isolated basic profile only.
            // It does not establish TEC affiliation or continuity with any older account.
            var profile = new ProfileDetails(id.value(), "Google", "");
            users.insert(User.create(id, evidence.verifiedEmail(), profile, URI.create("about:blank"), now()));
            identities.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, key, id,
                    evidence.verifiedEmail(), IdentityLinkStatus.ACTIVE));
            intents.replace(intent, intent.consume());
            if (!intent.validAt(now())) throw new SessionRejectedException();
            return evidence;
        });
        // A failure here never causes a retry of account creation. Login can use a fresh intent.
        var authenticated = sessions.createGoogle(verified);
        var token = jwt.issue(authenticated);
        var session = sessions.current(authenticated.sessionId()).orElseThrow(SessionRejectedException::new);
        return new GoogleLoginService.Result(token, session.sessionId(), session.userId().value(), session.deadline());
    }

    private String randomHex(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}

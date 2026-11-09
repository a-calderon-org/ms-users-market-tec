package com.markettec.users.application.session;

import com.markettec.users.application.ports.out.*;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** A verified Google assertion creates the account and session in one transaction. */
public final class GoogleRegistrationService {
    private final GoogleLoginIntentRepository intents;
    private final PendingGoogleRegistrationRepository pending;
    private final UserTransactions tx;
    private final GoogleIdentityVerifier verifier;
    private final UserRepository users;
    private final ExternalIdentityRepository identities;
    private final UserIdGenerator ids;
    private final SessionAuthority sessions;
    private final InternalTokenIssuer jwt;
    private final String audience;
    private final Clock clock;
    private final SecureRandom random;

    public GoogleRegistrationService(GoogleLoginIntentRepository intents, PendingGoogleRegistrationRepository pending,
            UserTransactions tx, GoogleIdentityVerifier verifier, UserRepository users,
            ExternalIdentityRepository identities, UserIdGenerator ids, SessionAuthority sessions,
            InternalTokenIssuer jwt, String audience, Clock clock, SecureRandom random) {
        this.intents = Objects.requireNonNull(intents);
        this.pending = Objects.requireNonNull(pending);
        this.tx = Objects.requireNonNull(tx);
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
        var browser = randomHex(32);
        var intent = new GoogleLoginIntent(randomHex(16), randomHex(32), digest(browser),
                now, now.plusSeconds(300), false, 0, GoogleLoginIntent.Purpose.REGISTRATION);
        tx.execute(() -> { intents.insert(intent); return null; });
        return new GoogleLoginService.Challenge(intent.attemptId(), intent.nonce(), browser, intent.expiresAt());
    }

    public boolean isRegistrationAttempt(String attemptId) {
        return tx.execute(() -> intents.loadLocked(attemptId)
                .map(i -> i.purpose() == GoogleLoginIntent.Purpose.REGISTRATION).orElse(false));
    }

    /** Account, exact identity link, consumed attempt and session commit together. */
    public GoogleLoginService.Result complete(String id, String browser, String token) {
        requireInputs(id, browser);
        if (token == null || token.isBlank() || token.length() > 16_384)
            throw new SessionRejectedException();
        return tx.execute(() -> {
            var intent = intent(id, browser);
            var proof = verifier.verify(token, new GoogleVerificationContext(audience, intent.nonce()));
            // Google sign-up trusts Google's authentication: any Google account with a verified email may
            // register. The institutional-domain rule applies to Magic Link (manual login) only.
            if (!intent.validAt(now()) || proof == null || !audience.equals(proof.audience())
                    || proof.verifiedEmail() == null)
                throw new SessionRejectedException();
            var key = new ExternalIdentityKey(proof.issuer(), proof.subject());
            if (identities.findByKey(key).isPresent()
                    || pending.historicalEmailExists(proof.verifiedEmail().normalized()))
                throw new SessionRejectedException(SessionRejectedException.Reason.IDENTITY_CONFLICT);
            var userId = ids.nextId();
            var user = User.create(userId, proof.verifiedEmail(),
                    new ProfileDetails(userId.value(), "Google", ""), URI.create("about:blank"), now());
            users.insert(user);
            users.replace(user, user.verifyProfileEmail(proof.verifiedEmail()));
            identities.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, key, userId,
                    proof.verifiedEmail(), IdentityLinkStatus.ACTIVE));
            intents.replace(intent, intent.consume());
            var authenticated = sessions.createGoogle(proof);
            var issued = sessions.issue(authenticated, jwt);
            if (!now().isBefore(intent.expiresAt()))
                throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_EXPIRED);
            return new GoogleLoginService.Result(issued.jwt(), issued.sessionId(), issued.userId(),
                    issued.sessionDeadline(), issued.refreshToken(), issued.csrfToken());
        });
    }

    private GoogleLoginIntent intent(String id, String browser) {
        var value = intents.loadLocked(id).orElseThrow(SessionRejectedException::new);
        if (value.consumed())
            throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_CONSUMED);
        if (!now().isBefore(value.expiresAt()))
            throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_EXPIRED);
        if (value.purpose() != GoogleLoginIntent.Purpose.REGISTRATION
                || !equal(value.browserHash(), digest(browser)))
            throw new SessionRejectedException();
        return value;
    }
    private static void requireInputs(String id, String browser) {
        if (id == null || !id.matches("[0-9a-f]{32}") || browser == null
                || !browser.matches("[0-9a-f]{64}")) throw new SessionRejectedException();
    }
    private String randomHex(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean equal(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(HexFormat.of().parseHex(a), HexFormat.of().parseHex(b));
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}

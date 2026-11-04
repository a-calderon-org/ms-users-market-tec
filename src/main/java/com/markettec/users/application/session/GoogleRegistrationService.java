package com.markettec.users.application.session;

import com.markettec.users.application.ports.out.*;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** A Google assertion only creates pending evidence; the mailbox POST authorizes account creation. */
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
    private final InstitutionalLinkSender mail;
    private final String audience;
    private final Clock clock;
    private final SecureRandom random;

    public GoogleRegistrationService(GoogleLoginIntentRepository intents, PendingGoogleRegistrationRepository pending,
            UserTransactions tx, GoogleIdentityVerifier verifier, UserRepository users,
            ExternalIdentityRepository identities, UserIdGenerator ids, SessionAuthority sessions,
            InternalTokenIssuer jwt, InstitutionalLinkSender mail, String audience, Clock clock, SecureRandom random) {
        this.intents = Objects.requireNonNull(intents);
        this.pending = Objects.requireNonNull(pending);
        this.tx = Objects.requireNonNull(tx);
        this.verifier = Objects.requireNonNull(verifier);
        this.users = Objects.requireNonNull(users);
        this.identities = Objects.requireNonNull(identities);
        this.ids = Objects.requireNonNull(ids);
        this.sessions = Objects.requireNonNull(sessions);
        this.jwt = Objects.requireNonNull(jwt);
        this.mail = Objects.requireNonNull(mail);
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

    /** Consumes the Google nonce. Ineligible and already linked identities get the same 202. */
    public Instant prepare(String id, String browser, String token) {
        return prepare(id, browser, token, UUID.randomUUID().toString());
    }

    public Instant prepare(String id, String browser, String token, String correlationId) {
        requireInputs(id, browser);
        if (correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,128}"))
            throw new SessionRejectedException();
        if (token == null || token.isBlank() || token.length() > 16_384) throw new SessionRejectedException();
        return tx.execute(() -> {
            var intent = intent(id, browser, false);
            var proof = verifier.verify(token, new GoogleVerificationContext(audience, intent.nonce()));
            if (!intent.validAt(now()) || proof == null || !audience.equals(proof.audience()))
                throw new SessionRejectedException();
            intents.replace(intent, intent.consume());
            Email email = proof.verifiedEmail();
            if (InstitutionalEmailProof.eligible(email)) {
                var key = new ExternalIdentityKey(proof.issuer(), proof.subject());
                if (identities.findByKey(key).isEmpty())
                    pending.insert(new PendingGoogleRegistration(id, correlationId, key.issuer(), key.subject(), email,
                            null, intent.expiresAt(), PendingGoogleRegistration.State.PENDING, 0));
            }
            return intent.expiresAt();
        });
    }

    /** The caller controls neither the recipient nor the link secret. Reissue awaits an abuse policy. */
    public void requestLink(String id, String browser, String requestedEmail) {
        requireInputs(id, browser);
        tx.execute(() -> {
            intent(id, browser, true);
            var state = pending.loadLocked(id).orElse(null);
            if (state == null || state.state() != PendingGoogleRegistration.State.PENDING
                    || requestedEmail == null || !state.recipient().normalized().equals(requestedEmail))
                return null;
            String secret = randomHex(32);
            pending.replace(state, state.withVerifier(linkDigest(state, secret)));
            mail.send(state.recipient(), id, secret);
            if (!now().isBefore(state.expiresAt()))
                throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_EXPIRED);
            return null;
        });
    }

    /** The outer transaction includes link consumption, user, identity, session, refresh and JWT. */
    public GoogleLoginService.Result confirm(String id, String browser, String token) {
        requireInputs(id, browser);
        if (token == null || !token.matches("[0-9a-f]{64}")) throw new SessionRejectedException();
        return tx.execute(() -> {
            var intent = intent(id, browser, true);
            var state = pending.loadLocked(id).orElseThrow(SessionRejectedException::new);
            if (state.state() == PendingGoogleRegistration.State.CONFIRMED)
                throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_CONSUMED);
            if (state.state() != PendingGoogleRegistration.State.LINK_ISSUED
                    || !InstitutionalEmailProof.eligible(state.recipient())
                    || !state.expiresAt().equals(intent.expiresAt())
                    || !equal(state.verifier(), linkDigest(state, token)))
                throw new SessionRejectedException();
            var key = new ExternalIdentityKey(state.issuer(), state.subject());
            if (identities.findByKey(key).isPresent()
                    || pending.historicalEmailExists(state.recipient().normalized()))
                throw new SessionRejectedException();
            var userId = ids.nextId();
            var user = User.create(userId, state.recipient(), new ProfileDetails(userId.value(), "Google", ""),
                    URI.create("about:blank"), now());
            users.insert(user);
            users.replace(user, user.verifyProfileEmail(state.recipient()));
            identities.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, key, userId,
                    state.recipient(), IdentityLinkStatus.ACTIVE));
            pending.replace(state, state.confirm());
            var proof = new VerifiedGoogleIdentity(state.issuer(), state.subject(), audience,
                    intent.createdAt(), intent.expiresAt(), state.recipient());
            var authenticated = sessions.createGoogle(proof);
            var issued = sessions.issue(authenticated, jwt);
            if (!now().isBefore(intent.expiresAt()))
                throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_EXPIRED);
            return new GoogleLoginService.Result(issued.jwt(), issued.sessionId(), issued.userId(),
                    issued.sessionDeadline(), issued.refreshToken(), issued.csrfToken());
        });
    }

    private GoogleLoginIntent intent(String id, String browser, boolean consumed) {
        var value = intents.loadLocked(id).orElseThrow(SessionRejectedException::new);
        if (!now().isBefore(value.expiresAt()))
            throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_EXPIRED);
        if (value.purpose() != GoogleLoginIntent.Purpose.REGISTRATION || value.consumed() != consumed
                || !equal(value.browserHash(), digest(browser)))
            throw new SessionRejectedException(value.consumed() && !consumed
                    ? SessionRejectedException.Reason.ATTEMPT_CONSUMED : SessionRejectedException.Reason.PROOF_INVALID);
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
    private static String linkDigest(PendingGoogleRegistration p, String token) {
        return digest("mtec-register-link-v1\0" + p.attemptId() + "\0" + p.issuer() + "\0"
                + p.subject() + "\0" + p.recipient().normalized() + "\0" + token);
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

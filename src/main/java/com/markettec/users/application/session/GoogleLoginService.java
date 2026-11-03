package com.markettec.users.application.session;

import com.markettec.users.application.ports.out.GoogleIdentityVerifier;
import com.markettec.users.application.ports.out.GoogleVerificationContext;
import com.markettec.users.application.ports.out.UserTransactions;
import com.markettec.users.application.ports.out.InternalTokenIssuer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

/** Internal login workflow. No HTTP route is registered until the browser contract is approved. */
public final class GoogleLoginService {
    private final GoogleLoginIntentRepository intents;
    private final UserTransactions transactions;
    private final GoogleIdentityVerifier verifier;
    private final SessionAuthority sessions;
    private final InternalTokenIssuer jwt;
    private final String audience;
    private final Clock clock;
    private final SecureRandom random;

    public GoogleLoginService(GoogleLoginIntentRepository intents, UserTransactions transactions,
                              GoogleIdentityVerifier verifier, SessionAuthority sessions,
                              InternalTokenIssuer jwt, String audience, Clock clock, SecureRandom random) {
        this.intents = Objects.requireNonNull(intents);
        this.transactions = Objects.requireNonNull(transactions);
        this.verifier = Objects.requireNonNull(verifier);
        this.sessions = Objects.requireNonNull(sessions);
        this.jwt = Objects.requireNonNull(jwt);
        if (audience == null || audience.isBlank() || audience.length() > 256)
            throw new IllegalArgumentException("Google login audience is required");
        this.audience = audience;
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    public Challenge begin() {
        String attemptId = randomHex(16);
        String nonce = randomHex(32);
        String browserSecret = randomHex(32);
        Instant now = now();
        var intent = new GoogleLoginIntent(attemptId, nonce, digest(browserSecret), now,
                now.plusSeconds(300), false, 0);
        transactions.execute(() -> { intents.insert(intent); return null; });
        return new Challenge(attemptId, nonce, browserSecret, intent.expiresAt());
    }

    public Result complete(String attemptId, String browserSecret, String idToken) {
        if (attemptId == null || !attemptId.matches("[0-9a-f]{32}")
                || browserSecret == null || !browserSecret.matches("[0-9a-f]{64}")
                || idToken == null || idToken.length() > 16_384 || idToken.isBlank())
            throw new SessionRejectedException();
        return transactions.execute(() -> {
            var intent = intents.loadLocked(attemptId).orElseThrow(SessionRejectedException::new);
            if (intent.consumed()) throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_CONSUMED);
            if (!now().isBefore(intent.expiresAt())) throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_EXPIRED);
            if (intent.purpose() != GoogleLoginIntent.Purpose.LOGIN || !MessageDigest.isEqual(
                    HexFormat.of().parseHex(intent.browserHash()), HexFormat.of().parseHex(digest(browserSecret))))
                throw new SessionRejectedException();
            var verified = verifier.verify(idToken, new GoogleVerificationContext(audience, intent.nonce()));
            if (!intent.validAt(now())) throw new SessionRejectedException();
            intents.replace(intent, intent.consume());
            var authenticated = sessions.createGoogle(verified);
            var issued = sessions.issue(authenticated, jwt);
            if (!intent.expiresAt().isAfter(now())) throw new SessionRejectedException();
            return new Result(issued.jwt(), issued.sessionId(), issued.userId(), issued.sessionDeadline(),
                    issued.refreshToken(), issued.csrfToken());
        });
    }

    private String randomHex(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String digest(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }

    /** Browser secret must be delivered through an approved secure browser binding. */
    public record Challenge(String attemptId, String nonce, String browserSecret, Instant expiresAt) { }
    public record Result(String jwt, String sessionId, String userId, Instant sessionDeadline,
                         String refreshToken, String csrfToken) {
        @Override public String toString() {
            return "Result[credentials=REDACTED, sessionId=" + sessionId + "]";
        }
    }
}

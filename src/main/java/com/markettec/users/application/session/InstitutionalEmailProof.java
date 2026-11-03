package com.markettec.users.application.session;

import com.markettec.users.domain.model.Email;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Internal challenge primitive. No delivery, persistence, or public redemption is wired. */
public final class InstitutionalEmailProof {
    private static final Set<String> DOMAINS = Set.of("estudiantec.cr", "itcr.ac.cr", "tec.ac.cr");
    private static final HexFormat HEX = HexFormat.of();

    private InstitutionalEmailProof() { }

    public static boolean eligible(Email recipient) {
        if (recipient == null) return false;
        String normalized = recipient.normalized();
        return DOMAINS.contains(normalized.substring(normalized.lastIndexOf('@') + 1));
    }

    public static Issued issue(Email recipient, RecentAttempt attempt, Instant now, SecureRandom random) {
        requireEligibleAttempt(recipient, attempt, now);
        Objects.requireNonNull(random);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String encoded = HEX.formatHex(secret);
        String verifier = digest(recipient, attempt, encoded);
        return new Issued(encoded, verifier);
    }

    public static boolean matches(Email recipient, RecentAttempt attempt, String verifier,
                                  String suppliedSecret, Instant now) {
        if (!eligible(recipient) || attempt == null || now == null || verifier == null
                || suppliedSecret == null || !verifier.matches("[0-9a-f]{64}")
                || !suppliedSecret.matches("[0-9a-f]{64}") || !live(attempt, now)) return false;
        byte[] expected = HEX.parseHex(verifier);
        byte[] actual = HEX.parseHex(digest(recipient, attempt, suppliedSecret));
        return MessageDigest.isEqual(expected, actual);
    }

    private static void requireEligibleAttempt(Email recipient, RecentAttempt attempt, Instant now) {
        if (!eligible(recipient) || attempt == null || now == null || !live(attempt, now))
            throw new SessionRejectedException();
    }

    private static boolean live(RecentAttempt attempt, Instant now) {
        return attempt.method() == RecentAttempt.Method.INSTITUTIONAL_EMAIL
                && !now.isBefore(attempt.createdAt())
                && now.isBefore(attempt.expiresAt())
                && now.isBefore(attempt.createdAt().plusSeconds(299))
                && attempt.authenticatedAt() == null && !attempt.consumed();
    }

    private static String digest(Email recipient, RecentAttempt attempt, String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{"MarketTec institutional email v1", recipient.normalized(),
                    attempt.userId().value(), attempt.sessionId(), attempt.attemptId(),
                    attempt.method().name(), attempt.operation(), attempt.target(), attempt.nonce(), secret}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                sha.update((byte) (bytes.length >>> 24));
                sha.update((byte) (bytes.length >>> 16));
                sha.update((byte) (bytes.length >>> 8));
                sha.update((byte) bytes.length);
                sha.update(bytes);
            }
            return HEX.formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Secret is transient delivery material; only verifier is suitable for storage. */
    public static final class Issued {
        private final String secret;
        private final String verifier;

        private Issued(String secret, String verifier) {
            this.secret = secret;
            this.verifier = verifier;
        }

        public String secret() { return secret; }
        public String verifier() { return verifier; }

        @Override public String toString() { return "Issued institutional email proof [redacted]"; }
    }
}

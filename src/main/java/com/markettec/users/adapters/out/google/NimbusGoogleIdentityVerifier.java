package com.markettec.users.adapters.out.google;

import com.markettec.users.application.ports.out.GoogleIdentityVerifier;
import com.markettec.users.application.ports.out.GoogleVerificationContext;
import com.markettec.users.application.ports.out.VerifiedGoogleIdentity;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyOperation;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import com.markettec.users.domain.model.Email;

/** Checks the entire Google token before exposing any claims. No account or session access. */
public final class NimbusGoogleIdentityVerifier implements GoogleIdentityVerifier {
    private static final int MAX_TOKEN_LENGTH = 16_384;
    private final GoogleTokenPolicy policy;
    private final GooglePublicKeys keys;
    private final Clock clock;

    public NimbusGoogleIdentityVerifier(GoogleTokenPolicy policy, GooglePublicKeys keys, Clock clock) {
        this.policy = Objects.requireNonNull(policy);
        this.keys = Objects.requireNonNull(keys);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public VerifiedGoogleIdentity verify(String idToken, GoogleVerificationContext context) {
        if (context == null || !policy.audiences().contains(context.expectedAudience()) ||
                idToken == null || idToken.length() > MAX_TOKEN_LENGTH || idToken.isBlank())
            throw new InvalidGoogleTokenException();
        try {
            SignedJWT jwt = SignedJWT.parse(idToken);
            JWSHeader header = jwt.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm()) || header.getKeyID() == null ||
                    header.getKeyID().isBlank() || header.getKeyID().length() > 128 ||
                    header.getJWKURL() != null || header.getX509CertURL() != null ||
                    header.getJWK() != null || header.getX509CertChain() != null ||
                    header.getCriticalParams() != null)
                throw new InvalidGoogleTokenException();

            RSAKey key = select(header.getKeyID(), false);
            if (key == null) key = select(header.getKeyID(), true);
            if (key == null || !jwt.verify(new RSASSAVerifier(key.toRSAPublicKey())))
                throw new InvalidGoogleTokenException();

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            String issuer = claims.getIssuer();
            String subject = claims.getSubject();
            List<String> audiences = claims.getAudience();
            String authorizedParty = claims.getStringClaim("azp");
            Date expiration = claims.getExpirationTime();
            Date issuedAt = claims.getIssueTime();
            Date notBefore = claims.getNotBeforeTime();
            Instant now = clock.instant();
            if (!policy.issuers().contains(issuer) || subject == null || subject.isBlank() ||
                    subject.length() > 255 || subject.chars().anyMatch(Character::isWhitespace) ||
                    audiences == null || audiences.isEmpty() || audiences.stream().anyMatch(a -> a == null || a.isBlank()) ||
                    !audiences.contains(context.expectedAudience()) || !policy.audiences().containsAll(audiences) ||
                    (audiences.size() > 1 && (authorizedParty == null || authorizedParty.isBlank())) ||
                    (authorizedParty != null && !policy.audiences().contains(authorizedParty)) ||
                    expiration == null || issuedAt == null ||
                    !expiration.toInstant().plus(policy.clockSkew()).isAfter(now) ||
                    issuedAt.toInstant().isAfter(now.plus(policy.clockSkew())) ||
                    (notBefore != null && notBefore.toInstant().isAfter(now.plus(policy.clockSkew()))) ||
                    issuedAt.after(expiration))
                throw new InvalidGoogleTokenException();
            if (context.expectedNonce() != null && !context.expectedNonce().equals(claims.getStringClaim("nonce")))
                throw new InvalidGoogleTokenException();
            Email verifiedEmail = null;
            if (Boolean.TRUE.equals(claims.getBooleanClaim("email_verified"))) {
                verifiedEmail = new Email(claims.getStringClaim("email"));
            }
            return new VerifiedGoogleIdentity(issuer, subject, context.expectedAudience(),
                    issuedAt.toInstant(), expiration.toInstant(), verifiedEmail,
                    profileName(claims, "given_name", 100), profileName(claims, "family_name", 160));
        } catch (InvalidGoogleTokenException e) {
            throw e;
        } catch (Exception e) {
            // Parse, key-fetch and claim-type failures share one safe error surface.
            throw new InvalidGoogleTokenException();
        }
    }

    /**
     * Optional display-name claim, only after the whole token verified. Anything that would not be a valid
     * profile field (non-string, blank, control characters, too long) is dropped, never truncated or guessed.
     */
    static String profileName(JWTClaimsSet claims, String name, int maxCodePoints) {
        if (!(claims.getClaim(name) instanceof String raw)) return null;
        String value = raw.strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maxCodePoints
                || value.codePoints().anyMatch(Character::isISOControl)) return null;
        return value;
    }

    private RSAKey select(String kid, boolean refresh) {
        RSAKey selected = null;
        for (JWK key : keys.keys(refresh).getKeys()) {
            if (!kid.equals(key.getKeyID())) continue;
            if (!(key instanceof RSAKey rsa) || rsa.isPrivate() ||
                    rsa.getModulus().decodeToBigInteger().bitLength() < 2048 ||
                    (rsa.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(rsa.getAlgorithm())) ||
                    (rsa.getKeyUse() != null && !KeyUse.SIGNATURE.equals(rsa.getKeyUse())) ||
                    (rsa.getKeyOperations() != null && !rsa.getKeyOperations().contains(KeyOperation.VERIFY)))
                throw new InvalidGoogleTokenException();
            if (selected != null) throw new InvalidGoogleTokenException();
            selected = rsa;
        }
        return selected;
    }
}

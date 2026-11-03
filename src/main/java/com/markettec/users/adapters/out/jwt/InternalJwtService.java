package com.markettec.users.adapters.out.jwt;

import com.markettec.users.application.session.AuthenticatedSession;
import com.markettec.users.application.session.Session;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.domain.model.UserId;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Internal RS256 tokens. Cryptographic validation does not check live session revocation. */
public final class InternalJwtService {
    private static final int MAX_TOKEN_CHARS = 8192;
    private final SessionAuthority sessions;
    private final InternalJwtKeyRing keys;
    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final SecureRandom random;

    public InternalJwtService(SessionAuthority sessions, InternalJwtKeyRing keys, String issuer,
                              String audience, Clock clock, SecureRandom random) {
        this.sessions = Objects.requireNonNull(sessions);
        this.keys = Objects.requireNonNull(keys);
        if (issuer == null || !issuer.matches("https://[^\\s]{1,248}")
                || audience == null || !audience.matches("[A-Za-z0-9:._/-]{1,256}"))
            throw new IllegalArgumentException("Invalid internal JWT issuer or audience");
        this.issuer = issuer;
        this.audience = audience;
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    /** The caller supplies a trusted authenticated session capability, never raw HTTP IDs. */
    public String issue(AuthenticatedSession authenticated) {
        Objects.requireNonNull(authenticated);
        String sid = authenticated.sessionId();
        if (sid == null || !sid.matches("[0-9a-f]{32}")) throw new SessionRejectedException();
        Session session = sessions.current(sid).orElseThrow(SessionRejectedException::new);
        Instant now = clock.instant();
        Instant deadline = session.deadline();
        Instant latest = now.plusSeconds(240);
        if (deadline.isBefore(latest)) latest = deadline;
        long expirySecond = latest.getEpochSecond();
        if (!sid.equals(session.sessionId()) || now.isBefore(session.createdAt())
                || !session.validAt(now) || expirySecond <= now.getEpochSecond())
            throw new SessionRejectedException();
        byte[] jtiBytes = new byte[16];
        random.nextBytes(jtiBytes);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(session.userId().value()).claim("sid", session.sessionId())
                .issuer(issuer).audience(audience).issueTime(Date.from(now))
                .expirationTime(Date.from(Instant.ofEpochSecond(expirySecond)))
                .jwtID(HexFormat.of().formatHex(jtiBytes)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(keys.active().getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(keys.active()));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Internal JWT signing failed", exception);
        }
    }

    public VerifiedToken verify(String compact) {
        if (compact == null || compact.length() > MAX_TOKEN_CHARS
                || compact.chars().filter(c -> c == '.').count() != 2
                || !compact.matches("[A-Za-z0-9_.-]+")) throw new InvalidInternalJwtException();
        try {
            SignedJWT jwt = SignedJWT.parse(compact);
            JWSHeader header = jwt.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm()) || header.getKeyID() == null
                    || header.getJWK() != null || header.getJWKURL() != null
                    || header.getX509CertURL() != null || header.getX509CertChain() != null
                    || header.getCriticalParams() != null || !header.getCustomParams().isEmpty())
                throw new InvalidInternalJwtException();
            var key = keys.publicKey(header.getKeyID());
            if (key == null || !jwt.verify(new RSASSAVerifier(key)))
                throw new InvalidInternalJwtException();
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            String sid = claims.getStringClaim("sid");
            String subject = claims.getSubject();
            String jti = claims.getJWTID();
            Date issued = claims.getIssueTime();
            Date expiry = claims.getExpirationTime();
            Instant now = clock.instant();
            if (!issuer.equals(claims.getIssuer()) || !List.of(audience).equals(claims.getAudience())
                    || sid == null || !sid.matches("[0-9a-f]{32}")
                    || jti == null || !jti.matches("[0-9a-f]{32}")
                    || issued == null || expiry == null || issued.after(Date.from(now))
                    || !expiry.after(issued) || !now.isBefore(expiry.toInstant())
                    || expiry.toInstant().isAfter(issued.toInstant().plusSeconds(240)))
                throw new InvalidInternalJwtException();
            return new VerifiedToken(new UserId(subject), sid, jti, issued.toInstant(), expiry.toInstant());
        } catch (ParseException | JOSEException | IllegalArgumentException exception) {
            throw new InvalidInternalJwtException();
        }
    }

    public record VerifiedToken(UserId userId, String sessionId, String jti, Instant issuedAt, Instant expiresAt) {}

    public static final class InvalidInternalJwtException extends RuntimeException {
        public InvalidInternalJwtException() { super("Invalid internal JWT"); }
    }
}

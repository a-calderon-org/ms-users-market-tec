package com.markettec.users.adapters.out.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markettec.users.application.session.AuthenticatedSession;
import com.markettec.users.application.session.Session;
import com.markettec.users.application.session.SessionAuthority;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.domain.model.UserId;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalJwtServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final String SID = "0123456789abcdef0123456789abcdef";
    private static final UserId USER = new UserId("usr_123e4567-e89b-42d3-a456-426614174000");
    private static KeyPair first;
    private static KeyPair second;
    private final SessionAuthority authority = mock(SessionAuthority.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeAll static void generateSyntheticKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        first = generator.generateKeyPair();
        second = generator.generateKeyPair();
    }

    private static String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(bytes) + "\n-----END " + type + "-----";
    }

    private static Map<String, String> material(String kid, KeyPair pair, boolean active) {
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());
        if (active) return Map.of("kid", kid, "publicKeyPem", publicPem,
                "privateKeyPem", pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        return Map.of("kid", kid, "publicKeyPem", publicPem);
    }

    private static InternalJwtKeyRing ring(String active, Map<String, String>... materials) {
        try {
            return InternalJwtKeyRing.fromJson(new ObjectMapper().writeValueAsString(materials), active);
        } catch (RuntimeException exception) { throw exception;
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private InternalJwtService service(InternalJwtKeyRing keys, Clock time) {
        return new InternalJwtService(authority, keys, "https://users.markettec.invalid",
                "markettec-api", time, new SecureRandom());
    }

    private InternalJwtService service() {
        return service(ring("new", material("new", first, true)), clock);
    }

    private static Session session() { return Session.start(SID, USER, NOW); }

    private static AuthenticatedSession authenticated(String sid) {
        AuthenticatedSession context = mock(AuthenticatedSession.class);
        when(context.sessionId()).thenReturn(sid);
        return context;
    }

    private String issue(InternalJwtService service, Session session) {
        when(authority.current(SID)).thenReturn(Optional.of(session));
        return service.issue(authenticated(SID));
    }

    @Test void issuesValidRs256TokenFromTrustedActiveSession() throws Exception {
        InternalJwtService service = service();
        String compact = issue(service, session());
        var claims = SignedJWT.parse(compact).getJWTClaimsSet();
        assertEquals(JWSAlgorithm.RS256, SignedJWT.parse(compact).getHeader().getAlgorithm());
        assertEquals("new", SignedJWT.parse(compact).getHeader().getKeyID());
        assertEquals(USER.value(), claims.getSubject());
        assertEquals(SID, claims.getStringClaim("sid"));
        assertEquals("https://users.markettec.invalid", claims.getIssuer());
        assertEquals(List.of("markettec-api"), claims.getAudience());
        assertEquals(NOW, claims.getIssueTime().toInstant());
        assertEquals(NOW.plusSeconds(240), claims.getExpirationTime().toInstant());
        assertEquals(java.util.Set.of("sub", "sid", "iss", "aud", "iat", "exp", "jti"),
                claims.getClaims().keySet());
        assertEquals(USER, service.verify(compact).userId());
        assertNotEquals(claims.getJWTID(), SignedJWT.parse(service.issue(authenticated(SID))).getJWTClaimsSet().getJWTID());
        verify(authority, times(2)).current(SID);
        assertFalse(compact.contains("@"));
    }

    @Test void rejectsRevokedSession() {
        Session revoked = session().revoke();
        assertThrows(SessionRejectedException.class, () -> issue(service(), revoked));
    }

    @Test void cryptographicVerificationDoesNotClaimLiveRevocationStatus() {
        InternalJwtService service = service();
        String token = issue(service, session());
        when(authority.current(SID)).thenReturn(Optional.empty());
        assertEquals(SID, service.verify(token).sessionId());
        verify(authority, times(1)).current(SID);
    }

    @Test void rejectsExpiredSession() {
        Session expired = Session.start(SID, USER, NOW.minusSeconds(3600));
        assertThrows(SessionRejectedException.class, () -> issue(service(), expired));
    }

    @Test void rejectsMissingAndFailedSessionAuthority() {
        InternalJwtService service = service();
        when(authority.current(SID)).thenReturn(Optional.empty());
        assertThrows(SessionRejectedException.class, () -> service.issue(authenticated(SID)));
        when(authority.current(SID)).thenThrow(new IllegalStateException("store unavailable"));
        assertThrows(IllegalStateException.class, () -> service.issue(authenticated(SID)));
    }

    @Test void tokenNeverExceeds240Seconds() {
        assertEquals(NOW.plusSeconds(240), service().verify(issue(service(), session())).expiresAt());
    }

    @Test void renewableDeadlineCapsExpiration() {
        Instant created = NOW.minusSeconds(3420);
        Session near = new Session(SID, USER, created, NOW.plusSeconds(180), NOW,
                NOW.plusSeconds(1800), created.plus(Session.ABSOLUTE), false, 1);
        InternalJwtService service = service();
        assertEquals(NOW.plusSeconds(180), service.verify(issue(service, near)).expiresAt());
    }

    @Test void idleDeadlineCapsExpiration() {
        Session near = Session.start(SID, USER, NOW.minusSeconds(1700));
        InternalJwtService service = service();
        assertEquals(NOW.plusSeconds(100), service.verify(issue(service, near)).expiresAt());
    }

    @Test void absoluteDeadlineCapsExpiration() {
        Instant created = NOW.minusSeconds(8 * 3600 - 120);
        Session near = new Session(SID, USER, created, NOW.plusSeconds(120), NOW,
                NOW.plusSeconds(1800), NOW.plusSeconds(120), false, 1);
        InternalJwtService service = service();
        assertEquals(NOW.plusSeconds(120), service.verify(issue(service, near)).expiresAt());
    }

    private String signed(KeyPair key, JWSAlgorithm algorithm, String kid, String issuer,
                          String audience, Instant expiry) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(USER.value()).claim("sid", SID)
                .issuer(issuer).audience(audience).issueTime(Date.from(NOW))
                .expirationTime(Date.from(expiry)).jwtID(SID).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(kid).build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }

    @Test void rejectsWrongSignature() throws Exception {
        assertThrows(InternalJwtService.InvalidInternalJwtException.class,
                () -> service().verify(signed(second, JWSAlgorithm.RS256, "new",
                        "https://users.markettec.invalid", "markettec-api", NOW.plusSeconds(100))));
    }

    @Test void rejectsWrongIssuerAndAudience() throws Exception {
        InternalJwtService service = service();
        assertThrows(InternalJwtService.InvalidInternalJwtException.class,
                () -> service.verify(signed(first, JWSAlgorithm.RS256, "new",
                        "https://wrong.invalid", "markettec-api", NOW.plusSeconds(100))));
        assertThrows(InternalJwtService.InvalidInternalJwtException.class,
                () -> service.verify(signed(first, JWSAlgorithm.RS256, "new",
                        "https://users.markettec.invalid", "other", NOW.plusSeconds(100))));
    }

    @Test void rejectsUnknownKidAndOtherAlgorithm() throws Exception {
        InternalJwtService service = service();
        assertThrows(InternalJwtService.InvalidInternalJwtException.class,
                () -> service.verify(signed(first, JWSAlgorithm.RS256, "unknown",
                        "https://users.markettec.invalid", "markettec-api", NOW.plusSeconds(100))));
        assertThrows(InternalJwtService.InvalidInternalJwtException.class,
                () -> service.verify(signed(first, JWSAlgorithm.RS384, "new",
                        "https://users.markettec.invalid", "markettec-api", NOW.plusSeconds(100))));
    }

    @Test void rejectsAtExactExpiryWithoutLeeway() throws Exception {
        String jwt = signed(first, JWSAlgorithm.RS256, "new", "https://users.markettec.invalid",
                "markettec-api", NOW.plusSeconds(100));
        InternalJwtService service = service(ring("new", material("new", first, true)),
                Clock.fixed(NOW.plusSeconds(100), ZoneOffset.UTC));
        assertThrows(InternalJwtService.InvalidInternalJwtException.class, () -> service.verify(jwt));
    }

    @Test void rotationOverlapsOldPublicAndNewSigningKey() throws Exception {
        InternalJwtKeyRing keys = ring("new", material("old", first, false), material("new", second, true));
        InternalJwtService service = service(keys, clock);
        assertEquals(USER, service.verify(signed(first, JWSAlgorithm.RS256, "old",
                "https://users.markettec.invalid", "markettec-api", NOW.plusSeconds(100))).userId());
        assertEquals("new", SignedJWT.parse(issue(service, session())).getHeader().getKeyID());
    }

    @Test void jwksContainsOnlyPublicMaterial() throws Exception {
        InternalJwtKeyRing keys = ring("new", material("old", first, false), material("new", second, true));
        String json = keys.publicJwksJson();
        assertEquals(2, JWKSet.parse(json).getKeys().size());
        assertFalse(json.contains("private"));
        assertFalse(json.contains("\"d\""));
        assertFalse(json.contains("PRIVATE KEY"));
        assertTrue(JWKSet.parse(json).getKeys().stream().noneMatch(k -> k.isPrivate()));
    }

    @Test void rejectsMalformedOversizedAndJoseUrls() throws Exception {
        InternalJwtService service = service();
        for (String token : List.of("", "bad", "a.b.c.d", " ", "a." + "a".repeat(8192) + ".c"))
            assertThrows(InternalJwtService.InvalidInternalJwtException.class, () -> service.verify(token));
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(USER.value()).claim("sid", SID)
                .issuer("https://users.markettec.invalid").audience("markettec-api")
                .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(100))).jwtID(SID).build();
        SignedJWT url = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("new")
                .jwkURL(java.net.URI.create("https://attacker.invalid/jwks")).build(), claims);
        url.sign(new RSASSASigner((RSAPrivateKey) first.getPrivate()));
        assertThrows(InternalJwtService.InvalidInternalJwtException.class, () -> service.verify(url.serialize()));
    }

    @Test void cannotIssueFromArbitraryUnauthenticatedIdentity() {
        InternalJwtService service = service();
        assertThrows(SessionRejectedException.class, () -> service.issue(authenticated("not-a-session")));
        when(authority.current(SID)).thenReturn(Optional.empty());
        assertThrows(SessionRejectedException.class, () -> service.issue(authenticated(SID)));
        assertThrows(NoSuchMethodException.class,
                () -> InternalJwtService.class.getMethod("issue", String.class, String.class));
        assertEquals(0, AuthenticatedSession.class.getConstructors().length);
    }

    @Test void rejectsWeakIncompleteAndMismatchedKeys() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> InternalJwtKeyRing.fromJson("[]", "new"));
        assertThrows(IllegalArgumentException.class, () -> ring("missing", material("new", first, false)));
        Map<String, String> mismatch = Map.of("kid", "new",
                "publicKeyPem", pem("PUBLIC KEY", first.getPublic().getEncoded()),
                "privateKeyPem", pem("PRIVATE KEY", second.getPrivate().getEncoded()));
        assertThrows(IllegalArgumentException.class, () -> ring("new", mismatch));
        KeyPairGenerator weakGenerator = KeyPairGenerator.getInstance("RSA");
        weakGenerator.initialize(1024);
        KeyPair weak = weakGenerator.generateKeyPair();
        assertThrows(IllegalArgumentException.class, () -> ring("weak", material("weak", weak, true)));
    }
}

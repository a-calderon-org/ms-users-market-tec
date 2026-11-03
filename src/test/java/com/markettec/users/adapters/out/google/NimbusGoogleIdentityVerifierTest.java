package com.markettec.users.adapters.out.google;

import com.markettec.users.application.ports.out.GoogleVerificationContext;
import com.markettec.users.application.ports.out.GoogleIdentityVerifier;
import com.markettec.users.config.GoogleVerifierConfig;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class NimbusGoogleIdentityVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private static final String AUD = "synthetic-client-id";
    private KeyPair pair;
    private RSAKey publicJwk;
    private MutableKeys keys;
    private NimbusGoogleIdentityVerifier verifier;
    private final GoogleVerificationContext context = new GoogleVerificationContext(AUD, null);

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        pair = generator.generateKeyPair();
        publicJwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .keyID("test-key").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        keys = new MutableKeys(new JWKSet(publicJwk));
        verifier = new NimbusGoogleIdentityVerifier(
                new GoogleTokenPolicy(Set.of(AUD, "synthetic-authorized-party"),
                        Set.of("https://accounts.google.com", "accounts.google.com"), Duration.ofSeconds(30)),
                keys, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void validTokenExposesOnlyExternalValidatedIdentifiers() throws Exception {
        String token = token(b -> b.claim("email", "admin@tec.ac.cr")
                .claim("email_verified", true).claim("hd", "tec.ac.cr")
                .claim("name", "Administrator").claim("picture", "https://example.test/a"));
        var identity = verifier.verify(token, context);
        assertEquals("google-subject", identity.subject());
        assertEquals("https://accounts.google.com", identity.issuer());
        assertEquals(AUD, identity.audience());
        assertEquals(NOW.minusSeconds(30), identity.issuedAt());
        assertEquals(NOW.plusSeconds(300), identity.expiresAt());
        assertEquals(5, identity.getClass().getRecordComponents().length);
        assertFalse(identity.toString().contains("admin@tec.ac.cr"));
        assertEquals(identity, verifier.verify(token(b -> b.claim("email", "other@example.org")
                .claim("email_verified", false).claim("hd", "other.example")), context));
        // No user repository, UserId or JWT issuer is involved in verification.
    }

    @Test
    void alteredSignatureIsRejected() throws Exception {
        String signed = token(b -> {});
        char last = signed.charAt(signed.length() - 2);
        rejects(signed.substring(0, signed.length() - 2) + (last == 'A' ? 'B' : 'A') + signed.substring(signed.length() - 1));
    }

    @Test
    void noneAndUnexpectedAlgorithmsAreRejected() throws Exception {
        rejects("eyJhbGciOiJub25lIiwia2lkIjoidGVzdC1rZXkifQ.eyJzdWIiOiJ4In0.");
        rejects(token(b -> {}, new JWSHeader.Builder(JWSAlgorithm.RS384).keyID("test-key").build()));
    }

    @Test
    void unknownAndIncompatibleKeysAreRejected() throws Exception {
        rejects(token(b -> {}, new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("unknown").build()));
        keys.set(new JWKSet(new RSAKey.Builder((RSAPublicKey) pair.getPublic()).keyID("test-key")
                .keyUse(KeyUse.ENCRYPTION).build()));
        rejects(token(b -> {}));
        keys.set(new JWKSet(List.of(publicJwk, publicJwk)));
        rejects(token(b -> {}));
    }

    @Test
    void issuerAudienceAndAuthorizedPartyAreStrict() throws Exception {
        rejects(token(b -> b.issuer("https://attacker.example")));
        rejects(token(b -> b.audience("different-client")));
        rejects(token(b -> b.claim("azp", "untrusted-client")));
        rejects(token(b -> b.audience(List.of(AUD, "another-client"))));
        assertEquals("google-subject", verifier.verify(token(b -> b.audience(List.of(AUD, "synthetic-authorized-party"))
                .claim("azp", "synthetic-authorized-party")), context).subject());
        rejectsWithContext(token(b -> {}), new GoogleVerificationContext("unconfigured-client", null));
    }

    @Test
    void expirationAndIssuedAtAreStrict() throws Exception {
        rejects(token(b -> b.expirationTime(Date.from(NOW.minusSeconds(31)))));
        rejects(token(b -> b.issueTime(Date.from(NOW.plusSeconds(31)))));
        rejects(token(b -> b.notBeforeTime(Date.from(NOW.plusSeconds(31)))));
        rejects(token(b -> b.claim("exp", null)));
        rejects(token(b -> b.claim("iat", null)));
        rejects(token(b -> b.issueTime(Date.from(NOW.plusSeconds(20)))
                .expirationTime(Date.from(NOW.plusSeconds(10)))));
    }

    @Test
    void subjectAndNonceAreStrict() throws Exception {
        rejects(token(b -> b.subject(null)));
        rejects(token(b -> b.subject(" ")));
        rejects(token(b -> b.subject("invalid subject")));
        rejectsWithContext(token(b -> {}), new GoogleVerificationContext(AUD, "expected-nonce"));
        rejectsWithContext(token(b -> b.claim("nonce", "wrong")),
                new GoogleVerificationContext(AUD, "expected-nonce"));
        assertEquals("google-subject", verifier.verify(token(b -> b.claim("nonce", "expected-nonce")),
                new GoogleVerificationContext(AUD, "expected-nonce")).subject());
    }

    @Test
    void keyRotationAndUnavailableKeysFailClosed() throws Exception {
        String signed = token(b -> {});
        keys.set(new JWKSet());
        keys.onRefresh = () -> keys.set(new JWKSet(publicJwk));
        assertEquals("google-subject", verifier.verify(signed, context).subject());
        assertEquals(1, keys.refreshes);
        keys.set(new JWKSet());
        keys.onRefresh = () -> {};
        rejects(signed);
    }

    @Test
    void malformedOversizedAndJoseKeyReferencesAreRejected() throws Exception {
        rejects("broken");
        rejects("x".repeat(16_385));
        rejects(token(b -> {}, new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key")
                .jwkURL(URI.create("https://attacker.example/jwks")).build()));
        rejects(token(b -> {}, new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key")
                .x509CertURL(URI.create("https://attacker.example/cert")).build()));
        rejects(token(b -> {}, new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key")
                .jwk(publicJwk).build()));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void errorsNeverEchoCredential(CapturedOutput output) throws Exception {
        String token = token(b -> b.issuer("secret-token-marker"));
        var error = assertThrows(InvalidGoogleTokenException.class, () -> verifier.verify(token, context));
        assertEquals("Invalid Google ID token", error.getMessage());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(token));
        assertFalse(output.getAll().contains(token));
    }

    @Test
    void policyRejectsUntrustedSourceAndMissingAudience() {
        assertThrows(IllegalArgumentException.class, () -> new GoogleTokenPolicy(Set.of(),
                Set.of("https://accounts.google.com"), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new GoogleTokenPolicy(Set.of(AUD),
                Set.of("https://other.example"), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new GoogleTokenPolicy(Set.of("*"),
                Set.of("https://accounts.google.com"), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new GoogleTokenPolicy(Set.of("client *"),
                Set.of("https://accounts.google.com"), Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new CachedGooglePublicKeys(
                URI.create("https://attacker.example/jwks"), null, Clock.systemUTC(),
                Duration.ofMinutes(10), Duration.ofSeconds(10)));
    }

    @Test
    void configurationFailsClosedWhenDisabledOrIncomplete() {
        var runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(GoogleVerifierConfig.class)
                .withBean(Clock.class, Clock::systemUTC);
        runner.run(context -> assertFalse(context.containsBean("googleIdentityVerifier")));
        runner.withPropertyValues("users.google.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("users.google.enabled=true", "users.google.audiences=*")
                .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("users.google.enabled=true", "users.google.audiences=" + AUD)
                .run(context -> assertNotNull(context.getBean(GoogleIdentityVerifier.class)));
    }

    private String token(Consumer<JWTClaimsSet.Builder> change) throws Exception {
        return token(change, new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build());
    }

    private String token(Consumer<JWTClaimsSet.Builder> change, JWSHeader header) throws Exception {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com").subject("google-subject")
                .audience(AUD).issueTime(Date.from(NOW.minusSeconds(30)))
                .expirationTime(Date.from(NOW.plusSeconds(300)));
        change.accept(builder);
        SignedJWT jwt = new SignedJWT(header, builder.build());
        JWSSigner signer = new RSASSASigner((RSAPrivateKey) pair.getPrivate());
        jwt.sign(signer);
        return jwt.serialize();
    }

    private void rejects(String token) {
        rejectsWithContext(token, context);
    }

    private void rejectsWithContext(String token, GoogleVerificationContext expected) {
        assertThrows(InvalidGoogleTokenException.class, () -> verifier.verify(token, expected));
    }

    private static final class MutableKeys implements GooglePublicKeys {
        private JWKSet current;
        private Runnable onRefresh = () -> {};
        private int refreshes;
        private MutableKeys(JWKSet current) { this.current = current; }
        void set(JWKSet current) { this.current = current; }
        @Override public JWKSet keys(boolean refresh) {
            if (refresh) { refreshes++; onRefresh.run(); }
            return current;
        }
    }
}

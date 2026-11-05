package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markettec.users.adapters.out.jwt.InternalJwtKeyRing;
import com.nimbusds.jose.jwk.JWKSet;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdentityMetadataControllerTest {
    private static InternalJwtKeyRing keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String publicPem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
        String privatePem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
        String json = new ObjectMapper().writeValueAsString(new Object[]{
                java.util.Map.of("kid", "active", "publicKeyPem", publicPem, "privateKeyPem", privatePem)});
        return InternalJwtKeyRing.fromJson(json, "active");
    }

    @Test void publishesOnlyPublicKeysAndContractMetadata() throws Exception {
        var controller = new IdentityMetadataController(keys(), "https://users.example.com",
                "markettec-api", "https://api.example.com/.well-known/jwks.json", new ObjectMapper());
        var jwks = controller.jwks();
        assertTrue(jwks.getHeaders().getCacheControl().contains("max-age=30"));
        assertEquals(1, JWKSet.parse(jwks.getBody().toString()).getKeys().size());
        assertFalse(JWKSet.parse(jwks.getBody().toString()).getKeys().getFirst().isPrivate());
        assertFalse(jwks.getBody().toString().contains("private"));
        var metadata = controller.metadata();
        assertEquals("https://api.example.com/.well-known/jwks.json", metadata.getBody().get("jwks_uri"));
        assertEquals("at+jwt", metadata.getBody().get("access_token_type"));
        assertEquals("0.3.0-draft", metadata.getBody().get("contract_version"));
        assertFalse(metadata.getBody().toString().contains("PRIVATE KEY"));
    }

    @Test void refusesUntrustedPublicUri() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new IdentityMetadataController(keys(),
                "https://users.example.com", "markettec-api", "http://example.com/.well-known/jwks.json",
                new ObjectMapper()));
    }
}

package com.markettec.users.adapters.out.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** External PEM material. Only the active key may contain a private key. */
public final class InternalJwtKeyRing {
    private final Map<String, RSAKey> publicKeys;
    private final RSAKey active;

    private InternalJwtKeyRing(Map<String, RSAKey> publicKeys, RSAKey active) {
        this.publicKeys = Map.copyOf(publicKeys);
        this.active = active;
    }

    public static InternalJwtKeyRing fromJson(String json, String activeKid) {
        if (json == null || json.isBlank() || json.length() > 131072 || !validKid(activeKid))
            throw new IllegalArgumentException("Incomplete internal JWT key configuration");
        try {
            JsonNode root = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(json);
            if (!root.isArray() || root.isEmpty() || root.size() > 16)
                throw new IllegalArgumentException("Invalid internal JWT key set");
            Map<String, RSAKey> publicKeys = new HashMap<>();
            RSAKey active = null;
            for (JsonNode node : root) {
                if (!node.isObject() || !validKid(text(node, "kid")))
                    throw new IllegalArgumentException("Invalid JWT kid");
                for (var fields = node.fieldNames(); fields.hasNext();) {
                    String field = fields.next();
                    if (!field.equals("kid") && !field.equals("publicKeyPem")
                            && !field.equals("privateKeyPem"))
                        throw new IllegalArgumentException("Unexpected JWT key field");
                }
                String kid = text(node, "kid");
                boolean isActive = kid.equals(activeKid);
                String privatePem = text(node, "privateKeyPem");
                if (isActive != (privatePem != null && !privatePem.isBlank())
                        || (!isActive && node.has("privateKeyPem")))
                    throw new IllegalArgumentException("Only active key requires private material");
                PublicKey parsed = KeyFactory.getInstance("RSA").generatePublic(
                        new X509EncodedKeySpec(decodePem(text(node, "publicKeyPem"), "PUBLIC KEY")));
                if (!(parsed instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength() < 2048
                        || rsa.getModulus().bitLength() > 8192
                        || rsa.getPublicExponent().bitLength() < 17 || !rsa.getPublicExponent().testBit(0))
                    throw new IllegalArgumentException("Weak RSA public key");
                RSAKey.Builder builder = new RSAKey.Builder(rsa).keyID(kid)
                        .algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE);
                if (isActive) {
                    PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(
                            new PKCS8EncodedKeySpec(decodePem(privatePem, "PRIVATE KEY")));
                    if (!(privateKey instanceof RSAPrivateKey rsaPrivate)
                            || rsaPrivate.getModulus().bitLength() < 2048
                            || !rsaPrivate.getModulus().equals(rsa.getModulus()))
                        throw new IllegalArgumentException("RSA private/public mismatch");
                    byte[] challenge = "MarketTec JWT key pair check".getBytes(StandardCharsets.US_ASCII);
                    Signature signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(privateKey);
                    signature.update(challenge);
                    byte[] signed = signature.sign();
                    signature.initVerify(rsa);
                    signature.update(challenge);
                    if (!signature.verify(signed)) throw new IllegalArgumentException("RSA key pair mismatch");
                    builder.privateKey(privateKey);
                    active = builder.build();
                }
                if (publicKeys.putIfAbsent(kid, builder.build().toPublicJWK()) != null)
                    throw new IllegalArgumentException("Duplicate JWT kid");
            }
            if (active == null) throw new IllegalArgumentException("Missing active JWT key");
            return new InternalJwtKeyRing(publicKeys, active);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid internal JWT key configuration", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static byte[] decodePem(String pem, String type) {
        if (pem == null || !pem.matches("(?s)-----BEGIN " + type + "-----\\s+[A-Za-z0-9+/=\\s]+-----END " + type + "-----\\s*"))
            throw new IllegalArgumentException("Invalid RSA PEM");
        String base64 = pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private static boolean validKid(String kid) {
        return kid != null && kid.matches("[A-Za-z0-9_-]{1,64}");
    }

    RSAKey active() { return active; }
    RSAKey publicKey(String kid) { return publicKeys.get(kid); }

    /** Public representation only; caller may expose it once an HTTP contract is agreed. */
    public String publicJwksJson() {
        return new JWKSet(new ArrayList<>(publicKeys.values())).toString();
    }
}

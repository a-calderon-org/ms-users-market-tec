package com.markettec.users.application.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Externally supplied, versioned CSRF HMAC keys. */
public final class CsrfKeys {
    private final Map<String, byte[]> keys;
    private final String activeKid;
    private CsrfKeys(Map<String, byte[]> keys, String activeKid) {
        this.keys = Map.copyOf(keys); this.activeKid = activeKid;
    }
    public static CsrfKeys fromJson(String json, String activeKid) {
        if (json == null || json.isBlank() || json.length() > 8192 || !validKid(activeKid))
            throw new IllegalArgumentException("Invalid CSRF key configuration");
        try {
            var root = new ObjectMapper().readTree(json);
            if (!root.isObject() || root.isEmpty() || root.size() > 16) throw new IllegalArgumentException("Invalid CSRF keys");
            Map<String, byte[]> keys = new HashMap<>();
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!validKid(field.getKey()) || !field.getValue().isTextual()) throw new IllegalArgumentException("Invalid CSRF key");
                byte[] key = Base64.getDecoder().decode(field.getValue().asText());
                if (key.length < 32 || key.length > 64) throw new IllegalArgumentException("Invalid CSRF key length");
                keys.put(field.getKey(), key);
            }
            if (!keys.containsKey(activeKid)) throw new IllegalArgumentException("Unknown active CSRF key");
            return new CsrfKeys(keys, activeKid);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid CSRF key configuration", e); }
    }
    public String activeKid() { return activeKid; }
    public String derive(String kid, String sessionId, long generation, String refreshHash) {
        byte[] key = keys.get(kid);
        if (key == null) throw new SessionRejectedException();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String message = "MarketTec:csrf:v1\n" + sessionId + "\n" + generation + "\n" + refreshHash;
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) { throw new IllegalStateException("CSRF derivation failed", e); }
    }
    private static boolean validKid(String kid) { return kid != null && kid.matches("[A-Za-z0-9_-]{1,32}"); }
}

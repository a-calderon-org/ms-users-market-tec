package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.markettec.users.adapters.out.jwt.InternalJwtKeyRing;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public verification material. This metadata is not OIDC discovery. */
@RestController
@ConditionalOnProperty(name = "users.internal-jwt.enabled", havingValue = "true")
public final class IdentityMetadataController {
    private final InternalJwtKeyRing keys;
    private final String issuer;
    private final String audience;
    private final String jwksUri;
    private final ObjectMapper mapper;

    public IdentityMetadataController(InternalJwtKeyRing keys,
            @Value("${users.internal-jwt.issuer}") String issuer,
            @Value("${users.internal-jwt.audience}") String audience,
            @Value("${users.internal-jwt.public-jwks-uri}") String jwksUri,
            ObjectMapper mapper) {
        this.keys = keys;
        this.issuer = requireHttps(issuer);
        this.audience = requireAudience(audience);
        this.jwksUri = requireHttps(jwksUri);
        if (!URI.create(this.jwksUri).getPath().endsWith("/.well-known/jwks.json"))
            throw new IllegalArgumentException("Invalid public JWKS URI");
        this.mapper = mapper;
    }

    private static String requireHttps(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Invalid public identity URI");
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid public identity URI");
        }
    }

    private static String requireAudience(String value) {
        if (value == null || !value.matches("[A-Za-z0-9:._/-]{1,256}"))
            throw new IllegalArgumentException("Invalid identity audience");
        return value;
    }

    @GetMapping("/.well-known/jwks.json")
    ResponseEntity<JsonNode> jwks() throws Exception {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(30)).cachePublic())
                .body(mapper.readTree(keys.publicJwksJson()));
    }

    @GetMapping("/.well-known/markettec-identity")
    ResponseEntity<Map<String, Object>> metadata() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(30)).cachePublic())
                .body(Map.of("issuer", issuer, "jwks_uri", jwksUri,
                        "audiences", List.of(audience), "access_token_algorithms", List.of("RS256"),
                        "access_token_type", "at+jwt", "contract_version", "0.3.0-draft"));
    }
}

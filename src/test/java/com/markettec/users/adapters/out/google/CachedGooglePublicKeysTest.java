package com.markettec.users.adapters.out.google;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CachedGooglePublicKeysTest {
    private static final URI GOOGLE = URI.create("https://www.googleapis.com/oauth2/v3/certs");

    @Test
    @SuppressWarnings("unchecked")
    void cacheHonorsMetadataBoundsAndRefreshesRotatedKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAKey first = new RSAKey.Builder((RSAPublicKey) generator.generateKeyPair().getPublic()).keyID("first").build();
        RSAKey second = new RSAKey.Builder((RSAPublicKey) generator.generateKeyPair().getPublic()).keyID("second").build();
        HttpClient http = mock(HttpClient.class);
        AtomicInteger requests = new AtomicInteger();
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            int count = requests.incrementAndGet();
            HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(new ByteArrayInputStream(new JWKSet(count == 1 ? first : second)
                    .toString().getBytes(StandardCharsets.UTF_8)));
            when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Cache-Control", List.of("public, max-age=300")),
                    (name, value) -> true));
            return response;
        });
        MutableClock clock = new MutableClock();
        var cache = new CachedGooglePublicKeys(GOOGLE, http, clock,
                Duration.ofSeconds(20), Duration.ofSeconds(2));
        assertEquals("first", cache.keys(false).getKeys().getFirst().getKeyID());
        assertEquals("first", cache.keys(false).getKeys().getFirst().getKeyID());
        assertEquals(1, requests.get());
        assertEquals("first", cache.keys(true).getKeys().getFirst().getKeyID());
        clock.advance(Duration.ofSeconds(3));
        assertEquals("second", cache.keys(true).getKeys().getFirst().getKeyID());
        assertEquals(2, requests.get());
        clock.advance(Duration.ofSeconds(21));
        assertEquals("second", cache.keys(false).getKeys().getFirst().getKeyID());
        assertEquals(3, requests.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyOrUnavailableJwksNeverBecomesTrusted() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(response.body()).thenAnswer(invocation -> new ByteArrayInputStream(new byte[0]));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var cache = new CachedGooglePublicKeys(GOOGLE, http, new MutableClock(),
                Duration.ofMinutes(1), Duration.ofSeconds(2));
        assertThrows(IllegalStateException.class, () -> cache.keys(false));
        assertThrows(IllegalStateException.class, () -> cache.keys(false));
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-11-02T12:00:00Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}

package com.markettec.users.adapters.out.google;

import com.nimbusds.jose.jwk.JWKSet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded JWKS cache; URL never comes from the credential or its JOSE header. */
public final class CachedGooglePublicKeys implements GooglePublicKeys {
    private static final Pattern MAX_AGE = Pattern.compile("(?:^|,)\\s*max-age=(\\d+)(?:,|$)");
    private static final int MAX_JWKS_BYTES = 131_072;
    private final URI uri;
    private final HttpClient http;
    private final Clock clock;
    private final Duration maxTtl;
    private final Duration refreshInterval;
    private JWKSet cached;
    private Instant expiresAt = Instant.EPOCH;
    private Instant lastFetch = Instant.EPOCH;

    public CachedGooglePublicKeys(URI uri, HttpClient http, Clock clock,
                                  Duration maxTtl, Duration refreshInterval) {
        if (!"https".equals(uri.getScheme()) ||
                !"www.googleapis.com".equals(uri.getHost()) ||
                !"/oauth2/v3/certs".equals(uri.getPath()) ||
                uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Unsupported Google JWKS source");
        if (maxTtl.isNegative() || maxTtl.isZero() || maxTtl.compareTo(Duration.ofHours(1)) > 0 ||
                refreshInterval.isNegative() || refreshInterval.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("Invalid Google JWKS cache policy");
        this.uri = uri;
        this.http = http;
        this.clock = clock;
        this.maxTtl = maxTtl;
        this.refreshInterval = refreshInterval;
    }

    @Override
    public synchronized JWKSet keys(boolean refresh) {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(expiresAt) &&
                (!refresh || now.isBefore(lastFetch.plus(refreshInterval)))) return cached;
        if (now.isBefore(lastFetch.plus(refreshInterval)))
            throw new IllegalStateException("Google JWKS refresh is rate limited");
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json").GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            lastFetch = now;
            byte[] body;
            try (InputStream input = response.body()) {
                body = input.readNBytes(MAX_JWKS_BYTES + 1);
            }
            if (response.statusCode() != 200 || body.length > MAX_JWKS_BYTES || body.length == 0)
                throw new IllegalStateException("Google JWKS unavailable");
            JWKSet parsed = JWKSet.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            if (parsed.getKeys().isEmpty()) throw new IllegalStateException("Google JWKS empty");
            String cacheControl = response.headers().firstValue("Cache-Control").orElse("").toLowerCase(Locale.ROOT);
            Matcher matcher = MAX_AGE.matcher(cacheControl);
            Duration ttl = matcher.find() ? Duration.ofSeconds(Long.parseLong(matcher.group(1))) : Duration.ZERO;
            if (cacheControl.contains("no-store") || cacheControl.contains("no-cache")) ttl = Duration.ZERO;
            if (ttl.compareTo(maxTtl) > 0) ttl = maxTtl;
            cached = parsed;
            expiresAt = now.plus(ttl);
            return parsed;
        } catch (Exception e) {
            lastFetch = now;
            // Do not retain stale keys, including on network or parsing failure.
            if (!now.isBefore(expiresAt)) cached = null;
            throw new IllegalStateException("Google JWKS unavailable");
        }
    }
}

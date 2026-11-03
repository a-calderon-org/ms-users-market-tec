package com.markettec.users.adapters.out.google;

import java.time.Duration;
import java.util.Set;

/** Server-owned allowlists. The two Google issuer spellings remain distinct after verification. */
public record GoogleTokenPolicy(Set<String> audiences, Set<String> issuers, Duration clockSkew) {
    private static final Set<String> GOOGLE_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    public GoogleTokenPolicy {
        audiences = Set.copyOf(audiences);
        issuers = Set.copyOf(issuers);
        if (audiences.isEmpty() || audiences.stream().anyMatch(a -> a.isBlank() ||
                a.indexOf('*') >= 0 || a.chars().anyMatch(Character::isWhitespace)))
            throw new IllegalArgumentException("Google audiences must be configured");
        if (issuers.isEmpty() || !GOOGLE_ISSUERS.containsAll(issuers))
            throw new IllegalArgumentException("Only Google issuers are allowed");
        if (clockSkew == null || clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("Google clock skew must be between zero and two minutes");
    }
}

package com.markettec.users.application.ports.out;

import java.util.Objects;

/** Must be constructed by trusted server code, never bound from an HTTP request. */
public record GoogleVerificationContext(String expectedAudience, String expectedNonce) {
    public GoogleVerificationContext {
        Objects.requireNonNull(expectedAudience);
        if (expectedAudience.isBlank()) throw new IllegalArgumentException("Expected audience is required");
        if (expectedNonce != null && expectedNonce.isBlank()) throw new IllegalArgumentException("Expected nonce is blank");
    }
}

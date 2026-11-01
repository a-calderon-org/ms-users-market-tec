package com.markettec.users.domain.model;

/** Exact, case-sensitive values. A trusted verifier owns issuer canonicalization. */
public record ExternalIdentityKey(String issuer, String subject) {
    public ExternalIdentityKey {
        requireOpaqueValue(issuer);
        requireOpaqueValue(subject);
    }

    private static void requireOpaqueValue(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Issuer and subject must be nonblank opaque values");
        }
    }
}

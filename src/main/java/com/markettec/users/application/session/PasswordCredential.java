package com.markettec.users.application.session;

import com.markettec.users.domain.model.UserId;
import java.time.Instant;
import java.util.Objects;

/** The adaptive hash of an account's password. Never carries plaintext. */
public record PasswordCredential(UserId userId, String hash, int hashVersion, Instant createdAt, Instant updatedAt,
                                 long version) {
    public PasswordCredential {
        Objects.requireNonNull(userId);
        if (hash == null || hash.isBlank() || hash.length() > 255 || hashVersion < 1 || createdAt == null
                || updatedAt == null || updatedAt.isBefore(createdAt) || version < 0)
            throw new IllegalArgumentException("Invalid password credential");
    }

    public PasswordCredential replaceHash(String newHash, int newVersion, Instant now) {
        return new PasswordCredential(userId, newHash, newVersion, createdAt, now, version + 1);
    }

    @Override public String toString() { return "PasswordCredential[hash=REDACTED]"; }
}

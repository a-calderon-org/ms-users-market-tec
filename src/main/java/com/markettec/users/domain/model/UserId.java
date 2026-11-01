package com.markettec.users.domain.model;

/** Internal storage format only; consumers must treat the entire value as opaque. */
public record UserId(String value) {
    public UserId {
        if (value == null || !value.matches(
                "usr_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Expected an opaque usr_ UUID v4 identifier");
        }
    }
}

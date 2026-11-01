package com.markettec.users.adapters.out.sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Matches HASHBYTES over SQL Server NVARCHAR's UTF-16LE binary representation. */
final class SqlIdentityKey {
    private SqlIdentityKey() { }

    static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_16LE));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

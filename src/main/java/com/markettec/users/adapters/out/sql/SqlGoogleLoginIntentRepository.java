package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.GoogleLoginIntent;
import com.markettec.users.application.session.GoogleLoginIntentRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Update lock and version check serialize a single login intent across instances. */
public final class SqlGoogleLoginIntentRepository implements GoogleLoginIntentRepository {
    private final JdbcTemplate jdbc;

    public SqlGoogleLoginIntentRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void insert(GoogleLoginIntent a) {
        requireTransaction();
        jdbc.update("""
            INSERT INTO dbo.google_login_intents(attempt_id,nonce,browser_hash,created_ms,expires_ms,consumed,version,purpose)
            VALUES (?,?,?,?,?,?,?,?)
            """, a.attemptId(), a.nonce(), a.browserHash(), a.createdAt().toEpochMilli(),
                a.expiresAt().toEpochMilli(), a.consumed(), a.version(), a.purpose().name());
    }

    @Override public Optional<GoogleLoginIntent> loadLocked(String id) {
        requireTransaction();
        if (id == null || !id.matches("[0-9a-f]{32}")) return Optional.empty();
        return jdbc.query("""
            SELECT attempt_id,nonce,browser_hash,created_ms,expires_ms,consumed,version,purpose
            FROM dbo.google_login_intents WITH (UPDLOCK,HOLDLOCK) WHERE attempt_id=?
            """, (rs, row) -> new GoogleLoginIntent(rs.getString("attempt_id").trim(),
                rs.getString("nonce").trim(), rs.getString("browser_hash").trim(),
                Instant.ofEpochMilli(rs.getLong("created_ms")), Instant.ofEpochMilli(rs.getLong("expires_ms")),
                rs.getBoolean("consumed"), rs.getLong("version"),
                GoogleLoginIntent.Purpose.valueOf(rs.getString("purpose"))), id).stream().findFirst();
    }

    @Override public void replace(GoogleLoginIntent before, GoogleLoginIntent after) {
        requireTransaction();
        if (!before.attemptId().equals(after.attemptId()) || !before.nonce().equals(after.nonce())
                || !before.browserHash().equals(after.browserHash()) || !before.createdAt().equals(after.createdAt())
                || !before.expiresAt().equals(after.expiresAt()) || before.purpose() != after.purpose() || !after.consumed()
                || after.version() != before.version() + 1)
            throw new UserPersistenceException("Invalid Google login intent update");
        int changed = jdbc.update("""
            UPDATE dbo.google_login_intents SET consumed=1,version=?
            WHERE attempt_id=? AND version=? AND consumed=0 AND purpose=?
            """, after.version(), before.attemptId(), before.version(), before.purpose().name());
        if (changed != 1) throw new UserPersistenceException("Concurrent or expired Google login intent");
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new UserPersistenceException("Google login transaction required");
    }
}

package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.RecentAttempt;
import com.markettec.users.application.session.RecentAttemptRepository;
import com.markettec.users.domain.model.UserId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Requires the session row lock before any attempt row access. */
public final class SqlRecentAttemptRepository implements RecentAttemptRepository {
    private final JdbcTemplate jdbc;
    public SqlRecentAttemptRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void insert(RecentAttempt a) {
        requireTransaction();
        jdbc.update("""
            INSERT INTO dbo.recent_authentication_attempts(attempt_id,user_id,session_id,method,operation,target,
                nonce,created_ms,expires_ms,authenticated_ms,consumed,version)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """, a.attemptId(), a.userId().value(), a.sessionId(), a.method().name(), a.operation(),
                a.target(), a.nonce(), a.createdAt().toEpochMilli(), a.expiresAt().toEpochMilli(),
                null, false, a.version());
    }

    @Override public Optional<RecentAttempt> loadLocked(String id) {
        requireTransaction();
        if (id == null || !id.matches("[0-9a-f]{32}")) return Optional.empty();
        return jdbc.query("""
            SELECT attempt_id,user_id,session_id,method,operation,target,nonce,created_ms,expires_ms,
                authenticated_ms,consumed,version
            FROM dbo.recent_authentication_attempts WITH (UPDLOCK,HOLDLOCK) WHERE attempt_id=?
            """, (rs, row) -> read(rs), id).stream().findFirst();
    }

    @Override public void replace(RecentAttempt before, RecentAttempt after) {
        requireTransaction();
        if (!before.attemptId().equals(after.attemptId()) || !before.userId().equals(after.userId())
                || !before.sessionId().equals(after.sessionId()) || before.method() != after.method()
                || !before.operation().equals(after.operation()) || !before.target().equals(after.target())
                || !before.nonce().equals(after.nonce()) || !before.createdAt().equals(after.createdAt())
                || !before.expiresAt().equals(after.expiresAt()) || after.version() != before.version() + 1)
            throw new UserPersistenceException("Invalid recent attempt update");
        int changed = jdbc.update("""
            UPDATE dbo.recent_authentication_attempts SET authenticated_ms=?,consumed=?,version=?
            WHERE attempt_id=? AND version=?
            """, after.authenticatedAt() == null ? null : after.authenticatedAt().toEpochMilli(),
                after.consumed(), after.version(), before.attemptId(), before.version());
        if (changed != 1) throw new UserPersistenceException("Concurrent recent attempt update");
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new UserPersistenceException("Recent attempt transaction required");
    }

    private static RecentAttempt read(ResultSet rs) throws SQLException {
        Long event = rs.getObject("authenticated_ms", Long.class);
        return new RecentAttempt(rs.getString("attempt_id").trim(), new UserId(rs.getString("user_id")),
                rs.getString("session_id").trim(), RecentAttempt.Method.valueOf(rs.getString("method")),
                rs.getString("operation"), rs.getString("target"), rs.getString("nonce").trim(),
                Instant.ofEpochMilli(rs.getLong("created_ms")), Instant.ofEpochMilli(rs.getLong("expires_ms")),
                event == null ? null : Instant.ofEpochMilli(event), rs.getBoolean("consumed"), rs.getLong("version"));
    }

}

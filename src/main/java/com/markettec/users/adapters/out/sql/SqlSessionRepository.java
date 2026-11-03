package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.Session;
import com.markettec.users.application.session.SessionRepository;
import com.markettec.users.domain.model.UserId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** SQL Server row update lock orders revocation and every session mutation until commit. */
public final class SqlSessionRepository implements SessionRepository {
    private final JdbcTemplate jdbc;

    public SqlSessionRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new UserPersistenceException("Session transaction required");
    }

    @Override public void insert(Session s) {
        requireTransaction();
        jdbc.update("""
            INSERT INTO dbo.sessions(session_id,user_id,created_ms,renewable_until_ms,last_activity_ms,
                idle_until_ms,absolute_until_ms,revoked,version) VALUES (?,?,?,?,?,?,?,?,?)
            """, s.sessionId(), s.userId().value(), ms(s.createdAt()), ms(s.renewableUntil()),
                ms(s.lastActivityAt()), ms(s.idleUntil()), ms(s.absoluteUntil()), s.revoked(), s.version());
    }

    @Override public Optional<Session> loadLocked(String id) {
        requireTransaction();
        Objects.requireNonNull(id);
        return jdbc.query("""
            SELECT session_id,user_id,created_ms,renewable_until_ms,last_activity_ms,
                idle_until_ms,absolute_until_ms,revoked,version
            FROM dbo.sessions WITH (UPDLOCK,HOLDLOCK) WHERE session_id=?
            """, (rs, row) -> read(rs), id).stream().findFirst();
    }

    @Override public void replace(Session before, Session after) {
        requireTransaction();
        if (!before.sessionId().equals(after.sessionId()) || !before.userId().equals(after.userId())
                || after.version() != before.version() + 1) throw new UserPersistenceException("Invalid session update");
        int changed = jdbc.update("""
            UPDATE dbo.sessions SET renewable_until_ms=?,last_activity_ms=?,idle_until_ms=?,
                revoked=?,version=? WHERE session_id=? AND version=?
            """, ms(after.renewableUntil()), ms(after.lastActivityAt()), ms(after.idleUntil()),
                after.revoked(), after.version(), before.sessionId(), before.version());
        if (changed != 1) throw new UserPersistenceException("Concurrent session update");
    }

    private static long ms(Instant value) { return value.toEpochMilli(); }
    private static Session read(ResultSet rs) throws SQLException {
        return new Session(rs.getString("session_id").trim(), new UserId(rs.getString("user_id")),
                Instant.ofEpochMilli(rs.getLong("created_ms")), Instant.ofEpochMilli(rs.getLong("renewable_until_ms")),
                Instant.ofEpochMilli(rs.getLong("last_activity_ms")), Instant.ofEpochMilli(rs.getLong("idle_until_ms")),
                Instant.ofEpochMilli(rs.getLong("absolute_until_ms")), rs.getBoolean("revoked"), rs.getLong("version"));
    }
}

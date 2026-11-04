package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.Session;
import com.markettec.users.application.session.SessionRepository;
import com.markettec.users.application.session.SessionVerifiers;
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

    @Override public Optional<Session> loadByRefreshHashLocked(String hash) {
        requireTransaction();
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new UserPersistenceException("Invalid verifier");
        return jdbc.query("""
            SELECT session_id,user_id,created_ms,renewable_until_ms,last_activity_ms,
                idle_until_ms,absolute_until_ms,revoked,version
            FROM dbo.sessions WITH (UPDLOCK,HOLDLOCK) WHERE refresh_hash=?
            """, (rs, row) -> read(rs), hash).stream().findFirst();
    }

    @Override public void replace(Session before, Session after) {
        requireTransaction();
        if (!before.sessionId().equals(after.sessionId()) || !before.userId().equals(after.userId())
                || after.version() != before.version() + 1) throw new UserPersistenceException("Invalid session update");
        int changed = jdbc.update("""
            UPDATE dbo.sessions SET renewable_until_ms=?,last_activity_ms=?,idle_until_ms=?,
                revoked=?,version=?,refresh_hash=CASE WHEN ?=1 THEN NULL ELSE refresh_hash END,
                csrf_hash=CASE WHEN ?=1 THEN NULL ELSE csrf_hash END
            WHERE session_id=? AND version=?
            """, ms(after.renewableUntil()), ms(after.lastActivityAt()), ms(after.idleUntil()),
                after.revoked(), after.version(), after.revoked(), after.revoked(),
                before.sessionId(), before.version());
        if (changed != 1) throw new UserPersistenceException("Concurrent session update");
    }

    @Override public Optional<SessionVerifiers> verifiers(String id) {
        requireTransaction();
        return jdbc.query("SELECT refresh_hash,csrf_hash FROM dbo.sessions WHERE session_id=?",
                (rs, row) -> rs.getString(1) == null ? null
                        : new SessionVerifiers(rs.getString(1).trim(), rs.getString(2).trim()), id)
                .stream().filter(Objects::nonNull).findFirst();
    }

    @Override public void initializeVerifiers(String id, SessionVerifiers values) {
        requireTransaction();
        int changed = jdbc.update("""
            UPDATE dbo.sessions SET refresh_hash=?,csrf_hash=?
            WHERE session_id=? AND refresh_hash IS NULL AND csrf_hash IS NULL AND revoked=0
            """, values.refreshHash(), values.csrfHash(), id);
        if (changed != 1) throw new UserPersistenceException("Session credentials already initialized");
    }

    @Override public void rotateVerifiers(String id, SessionVerifiers expected, SessionVerifiers replacement) {
        requireTransaction();
        int changed = jdbc.update("""
            UPDATE dbo.sessions SET refresh_hash=?,csrf_hash=?
            WHERE session_id=? AND refresh_hash=? AND csrf_hash=? AND revoked=0
            """, replacement.refreshHash(), replacement.csrfHash(), id,
                expected.refreshHash(), expected.csrfHash());
        if (changed != 1) throw new UserPersistenceException("Session credential rotation failed");
    }

    private static long ms(Instant value) { return value.toEpochMilli(); }
    private static Session read(ResultSet rs) throws SQLException {
        return new Session(rs.getString("session_id").trim(), new UserId(rs.getString("user_id")),
                Instant.ofEpochMilli(rs.getLong("created_ms")), Instant.ofEpochMilli(rs.getLong("renewable_until_ms")),
                Instant.ofEpochMilli(rs.getLong("last_activity_ms")), Instant.ofEpochMilli(rs.getLong("idle_until_ms")),
                Instant.ofEpochMilli(rs.getLong("absolute_until_ms")), rs.getBoolean("revoked"), rs.getLong("version"));
    }
}

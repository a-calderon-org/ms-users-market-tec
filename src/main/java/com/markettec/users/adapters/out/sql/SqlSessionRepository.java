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
            INSERT INTO dbo.sessions(session_id,user_id,created_ms,renewed_ms,renewable_until_ms,last_activity_ms,
                idle_until_ms,absolute_until_ms,revoked,version) VALUES (?,?,?,?,?,?,?,?,?,?)
            """, s.sessionId(), s.userId().value(), ms(s.createdAt()), ms(s.requireRenewedAt()), ms(s.renewableUntil()),
                ms(s.lastActivityAt()), ms(s.idleUntil()), ms(s.absoluteUntil()), s.revoked(), s.version());
    }

    @Override public Optional<Session> loadLocked(String id) {
        requireTransaction();
        Objects.requireNonNull(id);
        return jdbc.query("""
            SELECT session_id,user_id,created_ms,renewed_ms,renewable_until_ms,last_activity_ms,
                idle_until_ms,absolute_until_ms,revoked,version
            FROM dbo.sessions WITH (UPDLOCK,HOLDLOCK) WHERE session_id=?
            """, (rs, row) -> read(rs), id).stream().findFirst();
    }

    @Override public Optional<Session> loadByRefreshHashLocked(String hash) {
        requireTransaction();
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new UserPersistenceException("Invalid verifier");
        return jdbc.query("""
            SELECT session_id,user_id,created_ms,renewed_ms,renewable_until_ms,last_activity_ms,
                idle_until_ms,absolute_until_ms,revoked,version
            FROM dbo.sessions WITH (UPDLOCK,HOLDLOCK) WHERE refresh_hash=?
            """, (rs, row) -> read(rs), hash).stream().findFirst();
    }

    @Override public java.util.List<String> unrevokedSessionIds(com.markettec.users.domain.model.UserId userId,
                                                               java.time.Instant now) {
        requireTransaction();
        return jdbc.query("SELECT session_id FROM dbo.sessions WHERE user_id=? AND revoked=0 AND absolute_until_ms>? "
                + "ORDER BY session_id", (rs, row) -> rs.getString(1).trim(), userId.value(), ms(now));
    }

    @Override public void replace(Session before, Session after) {
        requireTransaction();
        if (!before.sessionId().equals(after.sessionId()) || !before.userId().equals(after.userId())
                || after.version() != before.version() + 1) throw new UserPersistenceException("Invalid session update");
        int changed = jdbc.update("""
            UPDATE dbo.sessions SET renewed_ms=?,renewable_until_ms=?,last_activity_ms=?,idle_until_ms=?,
                revoked=?,version=?,refresh_hash=CASE WHEN ?=1 THEN NULL ELSE refresh_hash END,
                csrf_hash=CASE WHEN ?=1 THEN NULL ELSE csrf_hash END,
                current_credential_generation=CASE WHEN ?=1 THEN NULL ELSE current_credential_generation END
            WHERE session_id=? AND version=?
            """, after.renewedAt() == null ? null : ms(after.renewedAt()),
                ms(after.renewableUntil()), ms(after.lastActivityAt()), ms(after.idleUntil()),
                after.revoked(), after.version(), after.revoked(), after.revoked(), after.revoked(),
                before.sessionId(), before.version());
        if (changed != 1) throw new UserPersistenceException("Concurrent session update");
    }

    @Override public Optional<SessionVerifiers> verifiers(String id) {
        requireTransaction();
        return jdbc.query("""
                SELECT s.refresh_hash,s.csrf_hash,g.generation,g.csrf_key_id FROM dbo.sessions s
                JOIN dbo.session_credential_generations g ON g.session_id=s.session_id
                    AND g.generation=s.current_credential_generation AND g.consumed_ms IS NULL
                WHERE s.session_id=?
                """,
                (rs, row) -> rs.getString(1) == null ? null
                        : rs.getString(4) == null ? null
                        : new SessionVerifiers(rs.getString(1).trim(), rs.getString(2).trim(), rs.getLong(3), rs.getString(4).trim()), id)
                .stream().filter(Objects::nonNull).findFirst();
    }

    @Override public void initializeVerifiers(String id, SessionVerifiers values) {
        requireTransaction();
        // The caller holds the session row lock. Both statements commit together.
        jdbc.update("""
            INSERT INTO dbo.session_credential_generations
                (session_id,generation,refresh_hash,csrf_hash,csrf_key_id) VALUES (?,0,?,?,?)
            """, id, values.refreshHash(), values.csrfHash(), values.keyId());
        int changed = jdbc.update("""
            UPDATE dbo.sessions SET refresh_hash=?,csrf_hash=?,current_credential_generation=0
            WHERE session_id=? AND refresh_hash IS NULL AND csrf_hash IS NULL
                AND current_credential_generation IS NULL AND revoked=0
            """, values.refreshHash(), values.csrfHash(), id);
        if (changed != 1) throw new UserPersistenceException("Session credentials already initialized");
    }

    @Override public void rotateVerifiers(String id, SessionVerifiers expected,
                                          SessionVerifiers replacement, Instant consumedAt) {
        requireTransaction();
        Long generation = jdbc.query("""
            SELECT current_credential_generation FROM dbo.sessions WITH (UPDLOCK,HOLDLOCK)
            WHERE session_id=? AND refresh_hash=? AND csrf_hash=? AND revoked=0
            """, rs -> rs.next() ? rs.getLong(1) : null,
                id, expected.refreshHash(), expected.csrfHash());
        if (generation == null || generation != expected.generation() || replacement.generation() != generation + 1)
            throw new UserPersistenceException("Session credential rotation failed");
        int consumed = jdbc.update("""
            UPDATE dbo.session_credential_generations SET consumed_ms=?
            WHERE session_id=? AND generation=? AND refresh_hash=? AND csrf_hash=? AND consumed_ms IS NULL
            """, ms(consumedAt), id, generation, expected.refreshHash(), expected.csrfHash());
        if (consumed != 1) throw new UserPersistenceException("Session credential rotation failed");
        jdbc.update("""
            INSERT INTO dbo.session_credential_generations
                (session_id,generation,refresh_hash,csrf_hash,csrf_key_id) VALUES (?,?,?,?,?)
            """, id, generation + 1, replacement.refreshHash(), replacement.csrfHash(), replacement.keyId());
        int changed = jdbc.update("""
            UPDATE dbo.sessions SET refresh_hash=?,csrf_hash=?,current_credential_generation=?
            WHERE session_id=? AND current_credential_generation=?
                AND refresh_hash=? AND csrf_hash=? AND revoked=0
            """, replacement.refreshHash(), replacement.csrfHash(), generation + 1, id, generation,
                expected.refreshHash(), expected.csrfHash());
        if (changed != 1) throw new UserPersistenceException("Session credential rotation failed");
    }

    @Override public Optional<ConsumedCredential> consumedByRefreshHashLocked(String hash) {
        requireTransaction();
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new UserPersistenceException("Invalid verifier");
        // Resolve without locking the generation first, then take the same session lock as current refresh.
        // Re-read under that lock so a concurrent winner is classified after its commit.
        var ids = jdbc.query("""
            SELECT session_id FROM dbo.session_credential_generations WHERE refresh_hash=?
            """, (rs, row) -> rs.getString(1).trim(), hash);
        if (ids.isEmpty()) return Optional.empty();
        Session session = loadLocked(ids.getFirst()).orElseThrow(() -> new UserPersistenceException("Missing session"));
        return jdbc.query("""
            SELECT csrf_hash,generation,csrf_key_id FROM dbo.session_credential_generations
            WHERE session_id=? AND refresh_hash=? AND consumed_ms IS NOT NULL
            """, (rs, row) -> new ConsumedCredential(session,
                new SessionVerifiers(hash, rs.getString(1).trim(), rs.getLong(2), rs.getString(3) == null ? "legacy" : rs.getString(3).trim())), session.sessionId(), hash)
                .stream().findFirst();
    }

    @Override public Optional<ConsumedCredential> closedByRefreshHashLocked(String hash) {
        requireTransaction();
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new UserPersistenceException("Invalid verifier");
        var ids = jdbc.query("""
            SELECT session_id FROM dbo.session_credential_generations WHERE refresh_hash=?
            """, (rs, row) -> rs.getString(1).trim(), hash);
        if (ids.isEmpty()) return Optional.empty();
        Session session = loadLocked(ids.getFirst()).orElseThrow(() -> new UserPersistenceException("Missing session"));
        return jdbc.query("""
            SELECT csrf_hash,generation,csrf_key_id FROM dbo.session_credential_generations
            WHERE session_id=? AND refresh_hash=? AND consumed_ms IS NULL
            """, (rs, row) -> new ConsumedCredential(session,
                new SessionVerifiers(hash, rs.getString(1).trim(), rs.getLong(2),
                    rs.getString(3) == null ? "legacy" : rs.getString(3).trim())), session.sessionId(), hash)
                .stream().findFirst();
    }

    private static long ms(Instant value) { return value.toEpochMilli(); }
    private static Session read(ResultSet rs) throws SQLException {
        return new Session(rs.getString("session_id").trim(), new UserId(rs.getString("user_id")),
                Instant.ofEpochMilli(rs.getLong("created_ms")),
                rs.getObject("renewed_ms", Long.class) == null ? null : Instant.ofEpochMilli(rs.getLong("renewed_ms")),
                Instant.ofEpochMilli(rs.getLong("renewable_until_ms")),
                Instant.ofEpochMilli(rs.getLong("last_activity_ms")), Instant.ofEpochMilli(rs.getLong("idle_until_ms")),
                Instant.ofEpochMilli(rs.getLong("absolute_until_ms")), rs.getBoolean("revoked"), rs.getLong("version"));
    }
}

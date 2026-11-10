package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.PasswordAttemptThrottle;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class SqlPasswordAttemptThrottle implements PasswordAttemptThrottle {
    private final JdbcTemplate jdbc;
    public SqlPasswordAttemptThrottle(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public Optional<State> loadLocked(String keyHash) {
        requireTransaction();
        requireKey(keyHash);
        return jdbc.query("SELECT failures,window_start_ms,locked_until_ms FROM dbo.password_attempt_throttle "
                + "WITH (UPDLOCK,HOLDLOCK) WHERE key_hash=?", (rs, row) -> new State(rs.getInt(1),
                Instant.ofEpochMilli(rs.getLong(2)), Instant.ofEpochMilli(rs.getLong(3))), keyHash)
                .stream().findFirst();
    }

    @Override public void save(String keyHash, State s) {
        requireTransaction();
        requireKey(keyHash);
        int changed = jdbc.update("UPDATE dbo.password_attempt_throttle SET failures=?,window_start_ms=?,locked_until_ms=? "
                + "WHERE key_hash=?", s.failures(), s.windowStart().toEpochMilli(), s.lockedUntil().toEpochMilli(), keyHash);
        if (changed == 0) jdbc.update("INSERT INTO dbo.password_attempt_throttle(key_hash,failures,window_start_ms,locked_until_ms) "
                + "VALUES (?,?,?,?)", keyHash, s.failures(), s.windowStart().toEpochMilli(), s.lockedUntil().toEpochMilli());
    }

    @Override public void clear(String keyHash) {
        requireTransaction();
        requireKey(keyHash);
        jdbc.update("DELETE FROM dbo.password_attempt_throttle WHERE key_hash=?", keyHash);
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new UserPersistenceException("Invalid throttle key");
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new UserPersistenceException("Password throttle transaction required");
    }
}

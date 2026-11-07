package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.EmailLoginAttempt;
import com.markettec.users.application.session.EmailLoginAttemptRepository;
import com.markettec.users.domain.model.Email;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class SqlEmailLoginAttemptRepository implements EmailLoginAttemptRepository {
    private final JdbcTemplate jdbc;
    public SqlEmailLoginAttemptRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void insert(EmailLoginAttempt a) {
        requireTransaction();
        jdbc.update("""
            INSERT INTO dbo.email_login_attempts(attempt_id,recipient,verifier,created_ms,expires_ms,failures,consumed,version)
            VALUES (?,?,?,?,?,?,?,?)
            """, a.attemptId(), a.recipient().address(), a.verifier(), a.createdAt().toEpochMilli(),
                a.expiresAt().toEpochMilli(), a.failures(), a.consumed(), a.version());
    }

    @Override public Optional<EmailLoginAttempt> loadLocked(String id) {
        requireTransaction();
        if (id == null || !id.matches("[0-9a-f]{32}")) return Optional.empty();
        return jdbc.query("""
            SELECT attempt_id,recipient,verifier,created_ms,expires_ms,failures,consumed,version
            FROM dbo.email_login_attempts WITH (UPDLOCK,HOLDLOCK) WHERE attempt_id=?
            """, (rs, row) -> new EmailLoginAttempt(rs.getString("attempt_id").trim(),
                new Email(rs.getString("recipient")), rs.getString("verifier").trim(),
                Instant.ofEpochMilli(rs.getLong("created_ms")), Instant.ofEpochMilli(rs.getLong("expires_ms")),
                rs.getInt("failures"), rs.getBoolean("consumed"), rs.getLong("version")), id)
                .stream().findFirst();
    }

    @Override public void replace(EmailLoginAttempt before, EmailLoginAttempt after) {
        requireTransaction();
        if (!before.attemptId().equals(after.attemptId()) || after.version() != before.version() + 1
                || before.consumed() && !after.consumed())
            throw new UserPersistenceException("Invalid email login update");
        int changed = jdbc.update("UPDATE dbo.email_login_attempts SET failures=?,consumed=?,version=? "
                + "WHERE attempt_id=? AND version=?", after.failures(), after.consumed(), after.version(),
                before.attemptId(), before.version());
        if (changed != 1) throw new UserPersistenceException("Concurrent email login update");
    }

    @Override public int countSince(String recipient, Instant since) {
        requireTransaction();
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.email_login_attempts WITH (UPDLOCK,HOLDLOCK) "
                + "WHERE LOWER(recipient)=LOWER(?) AND created_ms>=?", Integer.class, recipient, since.toEpochMilli());
        return n == null ? 0 : n;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new UserPersistenceException("Email login transaction required");
    }
}

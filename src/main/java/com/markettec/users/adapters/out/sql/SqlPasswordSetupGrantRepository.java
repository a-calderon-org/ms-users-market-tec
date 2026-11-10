package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.PasswordSetupGrantRepository;
import com.markettec.users.domain.model.UserId;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class SqlPasswordSetupGrantRepository implements PasswordSetupGrantRepository {
    private final JdbcTemplate jdbc;
    public SqlPasswordSetupGrantRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void issue(String sessionId, UserId userId, Instant createdAt, Instant expiresAt) {
        requireTransaction();
        jdbc.update("INSERT INTO dbo.password_setup_grants(session_id,user_id,created_ms,expires_ms,consumed) "
                + "VALUES (?,?,?,?,0)", sessionId, userId.value(), createdAt.toEpochMilli(), expiresAt.toEpochMilli());
    }

    @Override public boolean live(String sessionId, UserId userId, Instant now) {
        requireTransaction();
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.password_setup_grants WITH (UPDLOCK,HOLDLOCK) "
                + "WHERE session_id=? AND user_id=? AND consumed=0 AND expires_ms>?", Integer.class,
                sessionId, userId.value(), now.toEpochMilli());
        return n != null && n == 1;
    }

    @Override public boolean consume(String sessionId, UserId userId, Instant now) {
        requireTransaction();
        return jdbc.update("UPDATE dbo.password_setup_grants SET consumed=1 "
                + "WHERE session_id=? AND user_id=? AND consumed=0 AND expires_ms>?",
                sessionId, userId.value(), now.toEpochMilli()) == 1;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new UserPersistenceException("Password grant transaction required");
    }
}
